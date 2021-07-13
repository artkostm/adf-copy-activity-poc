package io.github.artkostm.data.publishing.schema.data

import cats.implicits._
import io.github.artkostm.data.schema.DataF._
import io.github.artkostm.data.schema.SchemaF._
import io.github.artkostm.data.publishing.schema.data.rule.avro._
import io.github.artkostm.data.schema.{DataF, SchemaF}
import higherkindness.droste.data.Fix
import higherkindness.droste.syntax.all.toFixSyntaxOps
import higherkindness.droste.{Gather, RAlgebra, scheme}
import jto.validation.{Path, Rule}
import org.apache.avro.generic.GenericRecord
import org.apache.avro.util.Utf8

import scala.util.Left

object avro {
  type RecordOrField = Either[GenericRecord, Any]
  type XRule[A]      = Rule[RecordOrField, A]
  type FixedDataRule = XRule[Fix[DataF]]

  lazy val validator: Fix[SchemaF] => XRule[Fix[DataF]] = scheme.gcata(dataValidationAlgebra)(Gather.para)

  protected val dataValidationAlgebra: RAlgebra[Fix[SchemaF], SchemaF, XRule[Fix[DataF]]] =
    RAlgebra[Fix[SchemaF], SchemaF, XRule[Fix[DataF]]] {
      case StructF(fields, _) =>
        fields.toList
          .traverse[XRule, (String, Fix[DataF])] {
            case (name, (_, validation)) =>
              (Path \ name).read(validation.map(fx => (name, fx)))
          }
          .map(fs => GStruct(fs.toMap).fix[DataF])
      case BooleanF(n)     => pickNullable[Boolean](n)(GBoolean(_).fix[DataF])
      case DoubleF(n)      => pickNullable[Double](n)(GDouble(_).fix[DataF])
      case FloatF(n)       => pickNullable[Float](n)(GFloat(_).fix[DataF])
      case IntF(n)         => pickNullable[Int](n)(GInt(_).fix[DataF])
      case LongF(n)        => pickNullable[Long](n)(GLong(_).fix[DataF])
      case StringF(n)      => pickNullable[Utf8](n)(utf8 => GString(utf8.toString).fix[DataF])
      case DatetimeF(n, f) => pickNullable[Long](n)(l => GDatetime(DatetimeF.toTs(l, f)).fix[DataF])
      case DateF(n)        => pickNullable[Int](n)(d => GDate(DatetimeF.toDate(d)).fix[DataF])
    }

  object RecordOrField {
    def apply(init: GenericRecord): RecordOrField = Left(init)
  }
}
