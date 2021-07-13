package io.github.artkostm.data.publishing

import io.github.artkostm.azure.loganalitycs.LoggingUtils
import io.github.artkostm.common.utils.EnvironmentUtils
import io.github.artkostm.data.publishing.config.{DatabaseConfig, DatabaseTransportConfig, FsConfigurationProp}
import io.github.artkostm.data.landing.rest.{config => C}
import io.github.artkostm.data.landing.rest.logger
import io.github.artkostm.data.publishing.transport.DatabaseTransport
import com.zaxxer.hikari.HikariConfig
import org.apache.hadoop.conf.Configuration
import org.slf4j.{Logger, LoggerFactory}
import zio.blocking.Blocking
import zio.{Has, ZLayer}

object Dependencies {
  protected implicit val LOGGER: Logger = LoggerFactory.getLogger(getClass)

  def apply(args: List[String]): ZLayer[Any, Throwable, DatabaseTransport] = {
    LoggingUtils.setUpSlf4jMDC(args.toArray)
    val appConfig: ZLayer[Any, Nothing, Has[DatabaseTransportConfig]] =
      config.configProvider >>> C
        .configLayer[DatabaseTransportConfig]
        .update[DatabaseTransportConfig](
          _.copy(launchId = EnvironmentUtils.getApplicationCorrelationId,
            appName = EnvironmentUtils.getApplicationName)
        )

    val hadoopFsConfig: ZLayer[Any, Nothing, Has[Configuration]] =
      appConfig.map(h => Has(FsConfigurationProp.toConfiguration(h.get[DatabaseTransportConfig].fs.fsConfig)))

    val hadoopFs = hadoopFsConfig >>> fs.hadoop
    val fileSystem = hadoopFs >>> fs.Fs.live

    val hikariPoolConfig: ZLayer[Any, Nothing, Has[HikariConfig]] =
      appConfig.map(h => Has(DatabaseConfig.toHikari(h.get[DatabaseTransportConfig].db)))

    val dbTransactor = (Blocking.live ++ hikariPoolConfig) >>> db.hikariTransactor
    val dbRepository = dbTransactor >>> db.Repository.doobie

    val transport = (fileSystem ++ dbRepository ++ appConfig) >>> DatabaseTransport.live

    transport.tapError(error => logger.error("Error building dependency graph", error))
  }
}
