package io.github.artkostm.data.publishing

import com.google.common.cache.{Cache, CacheBuilder}
import io.github.artkostm.data.publishing.config.DatabaseTransportConfig
import io.github.artkostm.data.publishing.db.Repository
import io.github.artkostm.data.publishing.fs.Fs
import io.github.artkostm.data.schema.DataF
import higherkindness.droste.data.Fix
import zio.{Chunk, Has, Task, ZIO, ZLayer}


package object transport {
  type DatabaseTransport = Has[DatabaseTransport.Service]

  final def run(): ZIO[DatabaseTransport, Throwable, Unit] =
    ZIO.accessM(_.get.run())

  final case class TransportStat(file: String, rowsRead: Int, rowsInserted: Int, rejected: Chunk[Fix[DataF]])
  final case class SimpleStat(file: String, rowsRead: Int, rowsInserted: Int)

  object DatabaseTransport {
    trait Service {
      def run(): Task[Unit]
    }

    def live: ZLayer[Fs with Repository with Has[DatabaseTransportConfig], Nothing, DatabaseTransport] =
      ZLayer.fromServicesM[Fs.Service, Repository.Service, DatabaseTransportConfig, Any, Nothing, DatabaseTransport.Service] {
        (fs, repo, config) => Ref.make {
          CacheBuilder.newBuilder().maximumSize(config.cacheSize).build().asInstanceOf[Cache[Fix[DataF], Boolean]]
        }.map(cache => new DatabaseTransportImpl(fs, repo, config, cache))
      }
  }
}
