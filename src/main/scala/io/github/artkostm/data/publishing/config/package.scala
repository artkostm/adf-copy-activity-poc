package io.github.artkostm.data.publishing

import io.github.artkostm.data.landing.rest.config.{AppConfig, AzureBlobConfig, ConfigProvider, PureConfig, Secret}
import com.zaxxer.hikari.HikariConfig
import org.apache.hadoop.conf.Configuration
import zio.{Layer, ZLayer}

import java.io.File
import java.util.{Properties, UUID}
import scala.language.postfixOps

// format: off
import pureconfig.configurable._
import pureconfig.generic.auto._
// format: on

package object config {
  import io.github.artkostm.data.landing.rest.config.PureConfig._

  sealed trait ProcessingMode
  final case object WithFallback extends ProcessingMode
  final case object Full         extends ProcessingMode

  final case class DatabaseTransportConfig(db: DatabaseConfig,
                                           fs: FileSystemConfig,
                                           dataSource: String,
                                           etl: String,
                                           directory: String,
                                           rejectedDirectory: String,
                                           fileGlob: Option[String],
                                           destinationTableName: String,
                                           monitoringConnectionKey: String,
                                           preCopyScript: Option[String] = None,
                                           keyColumns: Option[String] = None, // comma-separated list of columns
                                           statusColumn: Option[String] = None,
                                           cacheSize: Int = 1000,
                                           mode: ProcessingMode = WithFallback,
                                           parallelization: Int = 1,
                                           appName: String = "DbTransportApp",
                                           launchId: String = UUID.randomUUID().toString,
                                           rootDir: File = new File("out"),
                                           azure: AzureBlobConfig = AzureBlobConfig.default) extends AppConfig

  final case class FileSystemConfig(fsConfig: Seq[FsConfigurationProp] = Seq.empty)

  final case class FsConfigurationProp(key: Option[String],
                                       keyFormat: Option[String],
                                       value: Option[String],
                                       valueFormat: Option[String],
                                       keySecret: Option[Secret],
                                       valueSecret: Option[Secret])

  object FsConfigurationProp {
    def toConfiguration(configs: Seq[FsConfigurationProp]): Configuration =
      configs.map { prop =>
        val key = prop.key.orElse(
          for {
            secret <- prop.keySecret
            format <- prop.keyFormat
          } yield format.format(secret.value)
        ).getOrElse(throw new RuntimeException("Please check configuration for keys: " + prop))


        val value = prop.value.orElse(
          for {
            secret <- prop.valueSecret
            format <- prop.valueFormat
          } yield format.format(secret.value)
        ).getOrElse(throw new RuntimeException("Please check configuration for keys: " + prop))


        key -> value
      }.toMap.foldLeft(new Configuration()) {
        case (config, (k, v)) =>
          config.set(k, v)
          config
      }

  }

  final case class DatabaseConfig(connectionString: Secret, poolConfig: Map[String, String] = Map.empty, batchSize: Int = 100)

  object DatabaseConfig {
    def toHikari(dc: DatabaseConfig): HikariConfig = {
      val hikari = new HikariConfig(dc.poolConfig.foldLeft(new Properties()) {
        case (props, (k,v)) =>
          props.setProperty(k, v)
          props
      })
      hikari.setJdbcUrl(db.utils.jdbcConnectionStringFrom(dc.connectionString.value))

      hikari
    }
  }

  def configProvider: Layer[Nothing, ConfigProvider[DatabaseTransportConfig]] =
    ZLayer.succeed(new PureConfig[DatabaseTransportConfig])


}
