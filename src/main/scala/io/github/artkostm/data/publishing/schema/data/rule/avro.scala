package io.github.artkostm.data.publishing.schema.data.rule

import io.github.artkostm.data.schema.DataF
import io.github.artkostm.data.schema.DataF.nullF
import io.github.artkostm.data.publishing.schema.data.avro.RecordOrField
import higherkindness.droste.data.Fix
import jto.validation.{IdxPathNode, Invalid, KeyPathNode, Path, Rule, RuleLike, Valid, ValidationError}
import org.apache.commons.lang3.ClassUtils

import scala.reflect.ClassTag
import scala.util.Right

object avro {
  implicit def pickInRecord[II <: RecordOrField, O](p: Path)(
    implicit r: RuleLike[RecordOrField, O]): Rule[II, O] = {
    def search(path: Path, record: RecordOrField): Option[RecordOrField] = path.path match {
      case KeyPathNode(key) :: _ => Some(Right(record.fold(_.get(key).asInstanceOf[O], identity)))
      case IdxPathNode(_) :: _   => None
      case Nil                   => Some(record)
    }

    Rule[II, RecordOrField] { gr =>
      search(p, gr) match {
        case None     => Invalid(Seq(Path -> Seq(ValidationError("error.required"))))
        case Some(rv) => Valid(rv)
      }
    }.andThen(r)
  }

  implicit def castAny[T](implicit ct: ClassTag[T]): Rule[Any, T] = Rule.fromMapping[Any, T] {
    case t if ct.runtimeClass == t.getClass ||
      (ct.runtimeClass.isPrimitive && ClassUtils.primitiveToWrapper(ct.runtimeClass) == t.getClass) =>
      Valid(t.asInstanceOf[T])
    case t => Invalid(Seq(ValidationError("error.invalidType", s"cannot convert ${t.getClass} to ${ct.runtimeClass}")))
  }

  def pickNullable[O](nullable: Boolean)(ifNotNullable: O => Fix[DataF])(implicit r: RuleLike[Any, O], ct: ClassTag[O]): Rule[RecordOrField, Fix[DataF]] =
    Rule
      .fromMapping[RecordOrField, Fix[DataF]] { rec =>
        if (rec.isRight) {
          Option(rec.getOrElse(null)) match {
            case Some(data)        => r.validate(data).bimap(e => e.flatMap(_._2), ifNotNullable)
            case None if !nullable => Invalid(Seq(ValidationError("error.required")))
            case None if nullable  => Valid[Fix[DataF]](nullF(ct.runtimeClass.getSimpleName))
          }
        } else
          Invalid(Seq(ValidationError("error.invalid", "a leaf node can not be GenericRecord")))
      }
}
