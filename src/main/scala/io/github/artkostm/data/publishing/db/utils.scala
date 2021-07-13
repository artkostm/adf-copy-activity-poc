package io.github.artkostm.data.publishing.db

import org.slf4j.{Logger, LoggerFactory}

import java.sql.DriverManager

object utils {
  protected val LOGGER: Logger = LoggerFactory.getLogger(getClass)

  def jdbcConnectionStringFrom(connectionString: String): String = {
    try if (DriverManager.getDrivers.nextElement().acceptsURL(connectionString)) connectionString
        else {
          val props = connectionString.split(";").flatMap(_.split("=") match {
            case Array(name, value) => Some(name -> value)
            case _                  => None
          }).toMap
          s"jdbc:oracle:thin:${credentials(props).getOrElse("")}@${props.getOrElse("Host", "")}:${props.getOrElse("Port", "")}:${props.getOrElse("SID", "")}"
        }
    catch {
      case t: NoSuchElementException =>
        LOGGER.error("No DB driver registered!", t)
        throw new RuntimeException("No DB driver registered!")
    }
  }

  private def credentials(props: Map[String, String]): Option[String] =
    for {
      user     <- props.get("User Id")
      password <- props.get("Password")
    } yield s"$user/$password"
}

