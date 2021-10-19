package io.github.artkostm.data.publishing.transport

import com.google.common.cache.Cache
import io.github.artkostm.common.DataLakePathUtils
import io.github.artkostm.data.publishing.config.{Full, WithFallback}
import io.github.artkostm.data.publishing.config.DatabaseTransportConfig
import io.github.artkostm.data.publishing.db.Repository
import io.github.artkostm.data.publishing.fs.Fs
import io.github.artkostm.data.publishing.schema.utils
import io.github.artkostm.data.landing.rest.logger
import io.github.artkostm.data.schema.DataF
import higherkindness.droste.data.Fix
import org.slf4j.{Logger, LoggerFactory}
import zio.blocking.Blocking
import zio.stream.{Stream, Transducer, ZStream, ZTransducer}
import zio.{Chunk, Exit, Task, ZIO}

protected[transport] class DatabaseTransportImpl(fs: Fs.Service, repository: Repository.Service, config: DatabaseTransportConfig, deletedKeys: Ref[Cache[Fix[DataF], Boolean]])
  extends DatabaseTransport.Service {
  import DatabaseTransportImpl._

  override def run(): Task[Unit] = {
    config.preCopyScript.filter(_.trim.nonEmpty).map { script =>
      logger.info(s"Executing pre-copy script: $script") *>
        repository.executeScript(script) >>=
        (n => logger.info(s"Rows affected: $n"))
    }.getOrElse(Task.unit) *> ZIO.whenCase(config.mode) {
      case Full         => full()
      case WithFallback => withFallback()
    }
  }

  private def full(): Task[Unit] =
    for {
      _       <- logger.info(s"Getting listing for ${config.directory} (filePattern=${config.fileGlob})")
      files   <- fs.list(config.directory, config.fileGlob).provideLayer(Blocking.live)
      inserted <- repository.bulkInsert(
        config.destinationTableName,
        Stream.fromIterable(files.map(f => fs.readAvro(f.path)))
          .mapM(identity)
          .aggregate(Transducer.collectAllN(files.length))
          .flatMap(c => ZStream.concatAll(c.map { case (_, dataStream) => dataStream }))
          .provideLayer(Blocking.live)
      )
      dataPath = DataLakePathUtils.formRelationalPath(config.directory)
      _ <- logger.stateDb(config, config.etl, dataPath, "", "SUCCESS", inserted, inserted)
    } yield ()

  private def withFallback(): Task[Unit] =
    for {
      _       <- logger.info(s"Getting listing for ${config.directory} (filePattern=${config.fileGlob})")
      files   <- fs.list(config.directory, config.fileGlob).provideLayer(Blocking.live)
      workers = files.map(fs => processFile(fs.path, config.destinationTableName).run)
      results <- ZIO
        .collectAllWithParN(config.parallelization)(workers.toList) {
          case Exit.Success(ts) if ts.rowsRead == ts.rowsInserted =>
            LOGGER.info(s"Successfully processed file ${ts.file} [Read=${ts.rowsRead},Inserted=${ts.rowsInserted}]")
            Some(ts)
          case Exit.Success(ts) =>
            LOGGER.error(s"Failed to process file ${ts.file} [Read=${ts.rowsRead},Inserted=${ts.rowsInserted}]")
            Some(ts)
          case Exit.Failure(cause) =>
            LOGGER.error("There were some errors during processing", cause.squash)
            None
        }
        .provideLayer(Blocking.live)
      (read, inserted) = results.flatten.foldLeft((0, 0)) {
        case ((accRead, accInserted), SimpleStat(_, read, inserted)) => (accRead + read, accInserted + inserted)
      }
      dataPath = DataLakePathUtils.formRelationalPath(config.directory)
      rejectedPath = if (read != inserted) DataLakePathUtils.formRelationalPath(config.rejectedDirectory) else ""
      _ <- logger.stateDb(config, config.etl, dataPath, rejectedPath, "SUCCESS", read, inserted)
    } yield ()

  private def processFile(file: String, tableName: String): ZIO[Blocking, Throwable, SimpleStat] =
    for {
      _           <- logger.info(s"Processing $file...")
      (schema, stream) <- fs.readAvro(file)
      keyColumns = config.keyColumns.map(_.split(",").map(_.trim).filter(_.nonEmpty)).getOrElse(Array.empty)
      updatedStatOption <-
        (if (keyColumns.nonEmpty) {
          val groupedByKey = stream
            .groupByKey(data => DataF.struct(keyColumns.map(k => k -> data.getField(k)).toMap), buffer = config.db.batchSize) {
              case (key, dataStream) =>
                Stream.fromEffect(deletedKeys.get.flatMap { cache =>
                  ZIO.when(!Option(cache.getIfPresent(key)).getOrElse(false)) {
                    repository.deleteByKey(tableName, key).flatMap(c => logger.info(s"Deleted $c rows from $tableName [key=$key]"))
                      .catchAll(t => logger.error(s"Error while deleting from $tableName using key $key", t)) *>
                      deletedKeys.update { cache => cache.put(key, true); cache }
                  }
                }).flatMap(_ => dataStream)
            }
          config.statusColumn.filter(_.trim.nonEmpty).fold(groupedByKey) { statusColumn =>
            groupedByKey.filter(data => data.get(statusColumn).contains("INSERT")) // todo: move to enum
              .map {
                case Fix(GStruct(fields)) => DataF.struct(fields - statusColumn)
                case x => x
              }
          }
        } else stream)
                            .chunkN(config.db.batchSize)
                            .mapChunksM[Blocking, Throwable, TransportStat] { chunk =>
                              repository
                                .bulkInsertChunk(tableName, chunk)
                                .map {
                                  case (succeededRows, rejected) =>
                                    Chunk.succeed(TransportStat(file, chunk.size, succeededRows, rejected))
                                }
                            }
                            .groupByKey(_.file) {
                              case (_, statStream) =>
                                statStream.aggregate(ZTransducer.foldLeft((0, 0, Chunk.empty: Chunk[Fix[DataF]])) {
                                  case ((readAcc, insertedAcc, rejectedAcc), TransportStat(_, read, inserted, rejected)) =>
                                    (readAcc + read, insertedAcc + inserted, rejectedAcc ++ rejected)
                                })
                            }
                            .runHead
      schemaWithReason = utils.addReasonToSchema(schema)
      (read, inserted, rejected) = updatedStatOption.getOrElse((0, 0, Chunk.empty))
      _                          <- logger.info(s"File = $file: Rows read = $read, Rows inserted = $inserted")
      _                          <- fs.writeAvro(config.rejectedDirectory, Stream.fromChunk(rejected).chunkN(1000), schemaWithReason)
    } yield SimpleStat(file, read, inserted)
}

object DatabaseTransportImpl {
  protected implicit val LOGGER: Logger = LoggerFactory.getLogger(getClass)
}
