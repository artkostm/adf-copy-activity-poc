package io.github.artkostm.data.publishing.db

import _root_.doobie.Put
import _root_.doobie.enumerated.JdbcType
import cats.data.NonEmptyList
import io.github.artkostm.data.schema.DataF
import io.github.artkostm.data.schema.DataF._
import higherkindness.droste.data.Fix

import java.sql.{PreparedStatement, ResultSet, Types}

object doobie {

  val JdbcTypes = NonEmptyList.of(
    JdbcType.VarChar,
    JdbcType.Numeric,
    JdbcType.Float,
    JdbcType.Integer,
    JdbcType.Boolean,
    JdbcType.Date,
    JdbcType.Double,
    JdbcType.Timestamp,
    JdbcType.Null,
    JdbcType.NVarChar
  )

  val SchemaTypes = NonEmptyList.of(
    "VARCHAR2",
    "NUMBER",
    "NVARCHAR2",
    "DATE",
    "FLOAT",
    "TIMESTAMP"
  )

  implicit val writeData: Put[Fix[DataF]] = Put.Advanced.many[Fix[DataF]](
    JdbcTypes,
    SchemaTypes,
    put,
    update
  )

  def prepareInsertStatement(tblName: String, data: Fix[DataF]): String =
    Fix.un(data) match {
      case GStruct(fields) =>
        val keys = fields.keySet
        s"""INSERT INTO $tblName ${keys.mkString("(", ",", ")")} values ${List.fill(keys.size)("?").mkString("(", ",", ")")}"""
      case _ => throw new RuntimeException("High level object should be of GStruct type.")
    }

  def prepareDeleteStatement(tblName: String, key: Fix[DataF]): String =
    Fix.un(key) match {
      case GStruct(fields) =>
        val keys = fields.keySet
        s"""DELETE FROM $tblName WHERE ${keys.map(k => s"$k = ?").mkString(" AND ")}"""
      case _ => throw new RuntimeException("High level object should be of GStruct type.")
    }

  protected def put(ps: PreparedStatement, position: Int, data: Fix[DataF]): Unit =
    Fix.un(data) match {
      case GStruct(fields) =>
        fields.zipWithIndex.foreach {
          case ((_, Fix(GString(v))), i)   => ps.setString(i + 1, v)
          case ((_, Fix(GBoolean(v))), i)  => ps.setBoolean(i + 1, v)
          case ((_, Fix(GDouble(v))), i)   => ps.setDouble(i + 1, v)
          case ((_, Fix(GFloat(v))), i)    => ps.setFloat(i + 1, v)
          case ((_, Fix(GInt(v))), i)      => ps.setInt(i + 1, v)
          case ((_, Fix(GLong(v))), i)     => ps.setLong(i + 1, v)
          case ((_, Fix(GDatetime(v))), i) => ps.setTimestamp(i + 1, v)
          case ((_, Fix(GDate(v))), i)     => ps.setDate(i + 1, v)
          case ((_, Fix(GNull(t))), i)     => ps.setNull(i + 1, sqlType(t))
          case ((name, Fix(something)), _) =>
            throw new RuntimeException(s"Only flat structure can be inserted. Got $name: $something")
        }
      case _ => throw new RuntimeException("High level object should be of GStruct type.")
    }

  private def sqlType(tpe: String): Int = tpe.toLowerCase match {
    case "utf8" | "string" => Types.VARCHAR
    case "int" | "integer" => Types.INTEGER
    case "long"            => Types.NUMERIC
    case "double"          => Types.DOUBLE
    case "float"           => Types.FLOAT
    case "date"            => Types.DATE
    case "timestamp"       => Types.TIMESTAMP
    case "boolean"         => Types.BOOLEAN
    case _                 => Types.OTHER
  }

  protected def update(rs: ResultSet, position: Int, data: Fix[DataF]): Unit =
    Fix.un(data) match {
      case GStruct(fields) =>
        fields.zipWithIndex.foreach {
          case ((_, Fix(GString(v))), i)   => rs.updateString(i + 1, v)
          case ((_, Fix(GBoolean(v))), i)  => rs.updateBoolean(i + 1, v)
          case ((_, Fix(GDouble(v))), i)   => rs.updateDouble(i + 1, v)
          case ((_, Fix(GFloat(v))), i)    => rs.updateFloat(i + 1, v)
          case ((_, Fix(GInt(v))), i)      => rs.updateInt(i + 1, v)
          case ((_, Fix(GLong(v))), i)     => rs.updateLong(i + 1, v)
          case ((_, Fix(GDatetime(v))), i) => rs.updateTimestamp(i + 1, v)
          case ((_, Fix(GDate(v))), i)     => rs.updateDate(i + 1, v)
          case ((_, Fix(GNull(_))), i)     => rs.updateNull(i + 1)
          case ((name, Fix(something)), _) =>
            throw new RuntimeException(s"Only flat structure can be updated. Got $name: $something")
        }
      case _ => throw new RuntimeException("High level object should be of GStruct type.")
    }
}
