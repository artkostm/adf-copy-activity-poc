package io.github.artkostm.data.publishing

import io.github.artkostm.data.schema.{DataF, SchemaF}
import higherkindness.droste.data.Fix
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.FileSystem
import zio.blocking.Blocking
import zio.{Has, Managed, Task, ZIO, ZLayer}
import zio.stream.{Stream, ZStream}

package object fs {
  type Fs                   = Has[Fs.Service]
  type SchemaWithDataStream = (Fix[SchemaF], ZStream[Blocking, Throwable, Fix[DataF]])

  final case class FileStatus(path: String, isDir: Boolean)

  def hadoop: ZLayer[Has[Configuration], Throwable, Has[FileSystem]] =
    ZLayer.fromServiceManaged[Configuration, Any, Throwable, FileSystem] { config =>
      Managed.make(Task(FileSystem.get(config)))(fs => Task(fs.close()).ignore)
    }

  object Fs {
    trait Service {
      def readAvro(filePath: String): ZIO[Blocking, Throwable, SchemaWithDataStream]
      def writeAvro(fileDir: String, records: Stream[Throwable, Fix[DataF]], schema: Fix[SchemaF]): ZIO[Blocking, Throwable, Unit]
      def list(folderPath: String, glob: Option[String] = None): ZIO[Blocking, Throwable, Array[FileStatus]]
    }

    def live: ZLayer[Has[FileSystem], Nothing, Fs] =
      ZLayer.fromService[FileSystem, Fs.Service] { hadoopFs =>
        new HadoopFsImpl(hadoopFs)
      }
  }

  protected[fs] def autoCloseable[A <: AutoCloseable](res: => A): Managed[Throwable, A] =
    Managed.make(Task(res))(a => Task(a.close()).ignore)
}
