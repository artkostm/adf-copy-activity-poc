package io.github.artkostm.data.publishing.db


import com.google.common.cache.Cache
import _root_.doobie.implicits._
import _root_.doobie.util.log._
import _root_.doobie.{LogHandler, Transactor, _}
import cats.implicits._
import io.github.artkostm.data.landing.rest.logger
import io.github.artkostm.data.publishing.db.doobie._
import io.github.artkostm.data.publishing.schema.{utils => dataUtils}
import io.github.artkostm.data.schema.DataF
import higherkindness.droste.data.Fix
import org.slf4j.{Logger, LoggerFactory}
import zio.interop.catz._
import zio.interop.catz.core._
import zio.stream.ZTransducer
import zio.{Chunk, Task, stream}

class DoobieRepository(transactor: Transactor[Task]) extends Repository.Service {
  import DoobieRepository._

  override def bulkInsertChunk(tableName: String, chunk: Chunk[Fix[DataF]]): Task[(Int, Chunk[Fix[DataF]])] =
    chunk.headOption.map { first =>
      val insertStatement = prepareInsertStatement(tableName, first)
      doInsert(chunk) { c =>
        Update[Fix[DataF]](insertStatement).updateMany(c).transact(transactor)
      }
    }.getOrElse(Task.succeed((0, Chunk.empty)))

  override def bulkInsert(tableName: String, data: stream.Stream[Throwable, Fix[DataF]]): Task[Int] =
    data.mapChunks { chunk =>
      chunk.headOption.map { first =>
        Chunk.succeed(Update[Fix[DataF]](prepareInsertStatement(tableName, first)).updateMany(chunk))
      }.getOrElse(Chunk.empty)
    }.aggregate(ZTransducer.foldLeft(0.pure[ConnectionIO]) {
        case (acc, conIo) => acc *> conIo
      })
      .mapM(_.transact(transactor))
      .runSum

  override def executeScript(sqlScript: String): Task[Int] =
    Update[Unit](sqlScript, None).toUpdate0(()).run.transact(transactor)

  private def doInsert(
    chunk: Chunk[Fix[DataF]]
  )(dbCall: Chunk[Fix[DataF]] => Task[Int]): Task[(Int, Chunk[Fix[DataF]])] =
    dbCall(chunk).foldM[Any, Throwable, (Int, Chunk[Fix[DataF]])](
      error => {
        if (chunk.size <= 1)
          logger.error(s"Cannot insert the following data: \n\n$chunk\n\n", error) *>
            Task.succeed((0, chunk.map(dataUtils.addReasonToRow(_, error.getMessage))))
        else
          for {
            _                                           <- logger.error(s"Got error " + error.getMessage + ". Trying to re-insert the batch.")
            (left, right)                               = chunk.splitAt(chunk.size / 2)
            updatedLeftFiber                            <- doInsert(left)(dbCall).fork
            updatedRightFiber                           <- doInsert(right)(dbCall).fork
            ((updatedLeft, rejL), (updatedRight, rejR)) <- (updatedLeftFiber zip updatedRightFiber).join
          } yield (updatedLeft + updatedRight, rejL ++ rejR)
      },
      updatedRows => Task.succeed((chunk.size, Chunk.empty))
    )

  override def deleteByKey(tableName: String, key: Fix[DataF]): Task[Int] =
    Update[Fix[DataF]](prepareDeleteStatement(tableName, key))
      .run(key)
      .transact(transactor)
}

object DoobieRepository {
  protected implicit val LOGGER: Logger = LoggerFactory.getLogger(getClass)

  implicit val logHandler: LogHandler = LogHandler {
    case Success(s, a, e1, e2) =>
      LOGGER.info(s"""Successful Statement Execution:
                     |
                     |  ${s.linesIterator.dropWhile(_.trim.isEmpty).mkString("\n  ")}
                     |
                     | arguments = [${a.mkString(", ")}]
                     |   elapsed = ${e1.toMillis.toString} ms exec + ${e2.toMillis.toString} ms processing (${(e1 + e2).toMillis.toString} ms total)
          """.stripMargin)

    case ProcessingFailure(s, a, e1, e2, t) =>
      LOGGER.error(s"""Failed Resultset Processing:
                      |
                      |  ${s.linesIterator.dropWhile(_.trim.isEmpty).mkString("\n  ")}
                      |
                      | arguments = [${a.mkString(", ")}]
                      |   elapsed = ${e1.toMillis.toString} ms exec + ${e2.toMillis.toString} ms processing (failed) (${(e1 + e2).toMillis.toString} ms total)
                      |   failure = ${t.getMessage}
          """.stripMargin)

    case ExecFailure(s, a, e1, t) =>
      LOGGER.error(s"""Failed Statement Execution:
                      |
                      |  ${s.linesIterator.dropWhile(_.trim.isEmpty).mkString("\n  ")}
                      |
                      | arguments = [${a.mkString(", ")}]
                      |   elapsed = ${e1.toMillis.toString} ms exec (failed)
                      |   failure = ${t.getMessage}
          """.stripMargin)

  }
}
