package io.github.artkostm.data.publishing.fs

import cats.Id
import io.github.artkostm.data.landing.rest.logger
import io.github.artkostm.data.publishing.schema.{Avro, data}
import io.github.artkostm.data.schema.{DataF, SchemaF}
import higherkindness.droste.data.Fix
import org.apache.avro.Schema
import org.apache.avro.file.{DataFileStream, DataFileWriter}
import org.apache.avro.generic.{GenericDatumReader, GenericDatumWriter, GenericRecord}
import org.apache.hadoop.fs.{FileSystem, GlobFilter, Path}
import org.slf4j.{Logger, LoggerFactory}
import zio.blocking.Blocking
import zio.stream.Stream
import zio.{Managed, Task, ZIO}

import java.util.UUID

class HadoopFsImpl(fileSystem: FileSystem) extends Fs.Service {
  import HadoopFsImpl._

  override def readAvro(
    filePath: String
  ): ZIO[Blocking, Throwable, SchemaWithDataStream] =
    Task.effect {
      val in            = fileSystem.open(new Path(filePath))
      val datumReader   = new GenericDatumReader[GenericRecord]()
      val dfs           = new DataFileStream[GenericRecord](in, datumReader)
      val genericSchema = Avro.toSchemaF(dfs.getSchema)
      val stream = Stream.fromJavaIteratorManaged[Blocking, GenericRecord](
        Managed.make[Blocking, Blocking, Throwable, java.util.Iterator[GenericRecord] with java.io.Closeable](
          Task(dfs)
        )(dfs => Task(dfs.close()).ignore)
      )
      (genericSchema, stream.flatMap { genericRecord =>
        data.avro
          .validator(genericSchema)
          .validate(data.avro.RecordOrField(genericRecord))
          .fold(
            errors =>
              Stream.fail(
                new RuntimeException(
                  s"Error while validating Avro's generic record $genericRecord: \n${errors.mkString(",\n")}"
                )
            ),
            Stream.succeed(_)
          )
      })
    }

  override def list(folderPath: String, glob: Option[String]): ZIO[Blocking, Throwable, Array[FileStatus]] =
    Task.effect {
      glob
        .fold(fileSystem.listStatus(new Path(folderPath))) { filePattern =>
          fileSystem.listStatus(new Path(folderPath), new GlobFilter(filePattern))
        }
        .map(hfs => FileStatus(hfs.getPath.toString, hfs.isDirectory))
    }

  override def writeAvro(fileDirectory: String,
                         records: Stream[Throwable, Fix[DataF]],
                         schema: Fix[SchemaF]): ZIO[Blocking, Throwable, Unit] =
    Task.effect {
      val file      = new Path(fileDirectory)
      val parentDir = file.getParent
      if (!fileSystem.exists(parentDir)) fileSystem.mkdirs(parentDir)
      val avroSchema: Schema = Avro.toAvroSchema(schema)
      (file, avroSchema)
    }.flatMap {
      case (rootDirectory, avroSchema) =>
        records.foreachChunk { chunk =>
          val filePath = createFileName(rootDirectory)
          autoCloseable(fileSystem.create(filePath)).use { os =>
            logger.info(s"Creating file $filePath") *>
            autoCloseable {
              new DataFileWriter[GenericRecord](new GenericDatumWriter[GenericRecord](avroSchema))
            }.use { avroWriter =>
              Task(avroWriter.create(avroSchema, os))
                .map(
                  _ =>
                    chunk
                      .map(row => Avro.createGenericRecord[Id](schema, row))
                      .foreach(avroWriter.append)
                )
            }
          }
        }
    }

  private def createFileName(rootDir: Path): Path = new Path(rootDir, s"data_${UUID.randomUUID()}.avro")
}

object HadoopFsImpl {
  protected implicit val LOGGER: Logger = LoggerFactory.getLogger(getClass)
}
