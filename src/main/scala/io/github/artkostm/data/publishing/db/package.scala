package io.github.artkostm.data.publishing

import _root_.doobie.Transactor
import _root_.doobie.hikari.HikariTransactor
import cats.effect.Blocker
import io.github.artkostm.data.schema.DataF
import com.zaxxer.hikari.HikariConfig
import higherkindness.droste.data.Fix
import zio.blocking.Blocking
import zio.interop.catz._
import zio.{Chunk, Has, Task, ZIO, ZLayer}
import zio.stream.Stream

package object db {
  type Repository    = Has[Repository.Service]
  type ZioTransactor = Has[Transactor[Task]]

  def hikariTransactor: ZLayer[Blocking with Has[HikariConfig], Throwable, ZioTransactor] =
    ZLayer.fromManaged {
      for {
        config       <- ZIO.service[HikariConfig].toManaged_
        connectionEC <- ZIO.descriptor.map(_.executor.asEC).toManaged_
        blockingEC   <- zio.blocking.blocking(ZIO.descriptor.map(_.executor.asEC)).toManaged_
        transactor <- HikariTransactor
                       .fromHikariConfig[Task](
                         config,
                         connectionEC,
                         Blocker.liftExecutionContext(blockingEC)
                       )
                       .toManagedZIO
      } yield transactor
    }

  object Repository {

    def doobie: ZLayer[ZioTransactor, Throwable, Repository] =
      ZLayer.fromService[Transactor[Task], Repository.Service] { transactor =>
        new DoobieRepository(transactor)
      }

    trait Service {
      // trying to insert as many records as possible, transaction commit after each executeBatch
      def bulkInsertChunk(tableName: String, chunk: Chunk[Fix[DataF]]): Task[(Int, Chunk[Fix[DataF]])]
      // all or nothing
      def bulkInsert(tableName: String, data: Stream[Throwable, Fix[DataF]]): Task[Int]

      def executeScript(sqlScript: String): Task[Int]

      def deleteByKey(tableName: String, key: Fix[DataF]): Task[Int]
    }
  }
}
