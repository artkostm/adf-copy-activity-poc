package io.github.artkostm.data.publishing.db

import org.slf4j.{Logger, LoggerFactory}

import java.sql.DriverManager
import scala.collection.JavaConverters._

object utils {
  type ConnectionStringFormatter = String => String

  lazy val OracleConnectionStringFormatter: ConnectionStringFormatter =
    connectionString => {
      val props = connectionString.split(";").flatMap(_.split("=") match {
        case Array(name, value) => Some(name -> value)
        case _                  => None
      }).toMap
      s"jdbc:oracle:thin:${credentials(props).getOrElse("")}@${props.getOrElse("Host", "")}:${props.getOrElse("Port", "")}:${props.getOrElse("SID", "")}"
    }

  lazy val CustomCSFormatters: List[ConnectionStringFormatter] =
    OracleConnectionStringFormatter :: Nil

  protected val LOGGER: Logger = LoggerFactory.getLogger(getClass)

  def jdbcConnectionStringFrom(connectionString: String, customCSFormatters: List[ConnectionStringFormatter] = CustomCSFormatters): String = {
    try {
      val drivers = DriverManager.getDrivers.asScala.toArray
      if (drivers.exists(_.acceptsURL(connectionString))) connectionString
      else
        customCSFormatters.map(_.apply(connectionString)).find(cs => drivers.exists(_.acceptsURL(cs))).getOrElse(
          throw new RuntimeException("Cannot find the appropriate driver for the provided connection string.")
        )
    }
    catch {
      case t: Throwable =>
        LOGGER.error("Error while checking connection string", t)
        throw new RuntimeException("Error while checking connection string", t)
    }
  }

  private def credentials(props: Map[String, String]): Option[String] =
    for {
      user     <- props.get("User Id")
      password <- props.get("Password")
    } yield s"$user/$password"
}
