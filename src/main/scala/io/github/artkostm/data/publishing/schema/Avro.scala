package io.github.artkostm.data.publishing.schema

import cats.Monad
import cats.data.EitherT
import cats.implicits._
import io.github.artkostm.data.schema.DataF.{GArray, GDate, GDatetime, GStruct, zipWithSchema}
import io.github.artkostm.data.schema.SchemaF._
import io.github.artkostm.data.schema.{DataF, EnvT, GValue, SchemaF}
import higherkindness.droste._
import higherkindness.droste.data._
import higherkindness.droste.data.prelude._
import org.apache.avro.{LogicalTypes, Schema}
import org.apache.avro.Schema.Type
import org.apache.avro.generic.{GenericData, GenericRecord}
import org.slf4j.{Logger, LoggerFactory}

import java.time.{Instant, LocalDate}
import java.time.temporal.{ChronoField, ChronoUnit, TemporalField}
import scala.collection.JavaConverters._
import scala.language.higherKinds

object Avro {
  def createGenericRecord[M[_]: Monad](schema: Fix[SchemaF], data: Fix[DataF]): M[GenericRecord] =
    EitherT(scheme.hyloM(toGenericRecord[M], zipWithSchema[M]).apply((schema, data))).fold(
      a => {
        val error = new RuntimeException("The root struct cannot be of type Any")
        LOGGER.error(s"Error while creating avro.GenericRecord from $a", error)
        throw error
      },
      identity
    )

  lazy val toAvroSchema: Fix[SchemaF] => Schema = scheme.cata(fromSchemaFAlgebra)

  lazy val toSchemaF: Schema => Fix[SchemaF] = scheme.ana(toSchemaFCoalgebra)

  protected val LOGGER: Logger = LoggerFactory.getLogger(getClass)

  protected val fromSchemaFAlgebra: Algebra[SchemaF, Schema] = Algebra[SchemaF, Schema] {
    case StructF(fields, _) =>
      Schema
        .createRecord(s"record${Math.abs(fields.hashCode())}", null, "com.petrofac.data", false, fields.map {
          case (name, s) => new Schema.Field(name, s, null, null)
        }.toList.asJava)
    case ArrayF(element, n) => unionSchema(Schema.createArray(element), n)
    case BooleanF(n)        => unionSchema(Schema.create(Schema.Type.BOOLEAN), n)
    case DoubleF(n)         => unionSchema(Schema.create(Schema.Type.DOUBLE), n)
    case StringF(n)         => unionSchema(Schema.create(Schema.Type.STRING), n)
    case LongF(n)           => unionSchema(Schema.create(Schema.Type.LONG), n)
    case IntF(n)            => unionSchema(Schema.create(Schema.Type.INT), n)
    case DateF(n)           => unionSchema(LogicalTypes.date().addToSchema(Schema.create(Schema.Type.INT)), n)
    case DatetimeF(n, f)    =>
      unionSchema(f match {
        case DatetimeF.TimestampMicros => LogicalTypes.timestampMicros().addToSchema(Schema.create(Schema.Type.LONG))
      }, n)
  }

  private val toSchemaFCoalgebra: Coalgebra[SchemaF, Schema] = Coalgebra[SchemaF, Schema] { avro =>
    avro.getType match {
      case Schema.Type.RECORD => StructF(avro.getFields.asScala.map(f => f.name() -> f.schema()).toMap, false)
      case Schema.Type.ARRAY  => ArrayF(avro.getElementType, false)
      case Schema.Type.UNION =>
        avro.getTypes.asScala.find(_.getType != Schema.Type.NULL) match {
          case Some(s) if s.getLogicalType != null =>
            logicalType(s.getType, s.getLogicalType.getName, true)
          case Some(s) => primitiveType(s.getType, true)
          case None    => throw new RuntimeException("Union type should have at least one non-null type")
        }
      case tpe if avro.getLogicalType != null => logicalType(tpe, avro.getLogicalType.getName, true)
      case tpe                                => primitiveType(tpe, false)
    }
  }

  // monadic algebra to create a generic record out of M[EnvT[(SchemaF, DataF)]]
  protected def toGenericRecord[M[_]: Monad]: AlgebraM[M, EnvT, Either[Any, GenericRecord]] =
    AlgebraM[M, EnvT, Either[Any, GenericRecord]] {
      case AttrF(s @ Fix(StructF(_, _)), GStruct(values)) =>
        val avroSchema = toAvroSchema(s)
        val record     = new GenericData.Record(avroSchema)
        (Right(values.foldRight(record) {
          case ((name, Left(simpleValue)), rootRecord) =>
            rootRecord.put(name, simpleValue)
            rootRecord
          case ((name, Right(nestedRecord)), rootRecord) =>
            rootRecord.put(name, nestedRecord)
            rootRecord
        }): Either[Any, GenericRecord]).pure[M]
      case AttrF(_ @Fix(ArrayF(_, _)), GArray(values)) =>
        (Left(values.map {
          case Right(value) => value
          case Left(value)  => value
        }.asJava): Either[Any, GenericRecord]).pure[M]
      case AttrF(_, v: GDate[_]) =>
        (Left(ChronoUnit.DAYS.between(LocalDate.ofEpochDay(0), v.value.toLocalDate).toInt): Either[Any, GenericRecord]).pure[M]
      case AttrF(Fix(DatetimeF(_, f)), v: GDatetime[_]) =>
        f match {
          case DatetimeF.TimestampMicros => (Left(ChronoUnit.MICROS.between(Instant.EPOCH, v.value.toInstant)): Either[Any, GenericRecord]).pure[M]
        }
      case AttrF(_, v: GValue[_]) => (Left(v.value): Either[Any, GenericRecord]).pure[M]
      case AttrF(_, _)            => throw new RuntimeException("Should not happen")
    }

  private def unionSchema(s: Schema, nullable: Boolean): Schema =
    if (nullable) Schema.createUnion(s, Schema.create(Type.NULL)) else s

  private def logicalType(tpe: Schema.Type, logicalTypeName: String, nullable: Boolean): SchemaF[Schema] =
    (tpe, logicalTypeName) match {
      case (Schema.Type.LONG, "timestamp-micros") => DatetimeF(nullable, DatetimeF.TimestampMicros)
      case (Schema.Type.INT, "date")              => DateF(nullable)
      case tpe =>
        throw new RuntimeException(s"Cannot convert Avro's $tpe(LogicalType=$logicalTypeName) to SchemaF")
    }

  private def primitiveType(tpe: Schema.Type, nullable: Boolean): SchemaF[Schema] = tpe match {
    case Schema.Type.INT     => IntF(nullable)
    case Schema.Type.DOUBLE  => DoubleF(nullable)
    case Schema.Type.FLOAT   => FloatF(nullable)
    case Schema.Type.LONG    => LongF(nullable)
    case Schema.Type.BOOLEAN => BooleanF(nullable)
    case Schema.Type.STRING  => StringF(nullable)
    case tpe                 => throw new RuntimeException(s"Cannot convert Avro's $tpe to SchemaF")
  }
}
