package io.github.artkostm.data.schema

import cats.implicits._
import cats.{Applicative, Functor, Monad, Traverse}
import io.github.artkostm.data.schema.DataF._
import io.github.artkostm.data.schema.SchemaF.{ArrayF, StructF}
import higherkindness.droste.CoalgebraM
import higherkindness.droste.data.{AttrF, Fix}
import higherkindness.droste.syntax.all.toFixSyntaxOps
import higherkindness.droste.util.DefaultTraverse

import java.sql.{Date, Timestamp}
import scala.language.higherKinds

sealed trait DataF[A] {
  def traverse[F[_]: Applicative, B](f: A => F[B]): F[DataF[B]] = this match {
    case GStruct(fields) =>
      fields
        .foldRight(Map.empty[String, B].pure[F]) {
          case ((name, v), acc) =>
            (name.pure[F], f(v), acc).mapN { (n, a, m) =>
              m + (n -> a)
            }
        }
        .map(GStruct(_))
    case GArray(elements) =>
      elements.foldRight(Seq.empty[B].pure[F]) {
        case (e, acc) => (f(e), acc).mapN(_ +: _)
      }.map(GArray.apply)
    case GDouble(n)  => (GDouble[B](n): DataF[B]).pure[F]
    case GBoolean(n) => (GBoolean[B](n): DataF[B]).pure[F]
    case GFloat(n)   => (GFloat[B](n): DataF[B]).pure[F]
    case GString(n)  => (GString[B](n): DataF[B]).pure[F]
    case GLong(n)    => (GLong[B](n): DataF[B]).pure[F]
    case GInt(n)     => (GInt[B](n): DataF[B]).pure[F]
    case GNull(t)     => (GNull[B](t): DataF[B]).pure[F]
    case GDatetime(n) => (GDatetime[B](n): DataF[B]).pure[F]
    case GDate(n)     => (GDate[B](n): DataF[B]).pure[F]
  }
}

sealed trait GValue[A] extends DataF[A] {
  val value: Any
}

object DataF {
  implicit class FixedData(d: Fix[DataF]) {
    def put(name: String, value: Fix[DataF]): Fix[DataF] = d match {
      case Fix(s: GStruct[Fix[DataF]]) => s.put(name, value).fix[DataF]
      case x => throw new UnsupportedOperationException(s"Cannot put value $value to $x")
    }

    def get(fieldName: String): Option[Any] = {
      def search(dd: Fix[DataF]): Option[Fix[DataF]] = dd match {
        case Fix(GStruct(fields))  => fields.get(fieldName).flatMap(search)
        case f @ Fix(_: GValue[_]) => Some(f)
        case _                     => None
      }

      search(d).flatMap(Fix.un(_) match {
        case field: GValue[_] => Option(field.value)
        case _                => None
      })
    }
  }

  def isNotNull(d: Fix[DataF]): Boolean =
    Fix.un(d) match {
      case GNull(_) => false
      case _        => true
    }

  val nullF: Fix[DataF] = GNull("").fix[DataF]

  def nullF(tpe: String): Fix[DataF] = GNull(tpe).fix[DataF]

  final case class GStruct[A](fields: Map[String, A]) extends DataF[A] {
    def put(name: String, value: A): GStruct[A] = GStruct[A](fields + (name -> value))
  }
  final case class GArray[A](elements: Seq[A])    extends DataF[A]
  final case class GString[A](value: String)      extends GValue[A]
  final case class GBoolean[A](value: Boolean)    extends GValue[A]
  final case class GDouble[A](value: Double)      extends GValue[A]
  final case class GFloat[A](value: Float)        extends GValue[A]
  final case class GInt[A](value: Int)            extends GValue[A]
  final case class GLong[A](value: Long)          extends GValue[A]
  final case class GDatetime[A](value: Timestamp) extends GValue[A]
  final case class GDate[A](value: Date)          extends GValue[A]

  final case class GNull[A](tpe: String) extends GValue[A] {
    override val value: Any = null
  }

  // zip SchemaF with DataF - we need this because it is not possible to construct avro's generic record without schema
  def zipWithSchema[M[_]: Monad]: CoalgebraM[M, EnvT, (Fix[SchemaF], Fix[DataF])] =
    CoalgebraM[M, EnvT, (Fix[SchemaF], Fix[DataF])] {
      case (structF @ Fix(StructF(fields, _)), Fix(GStruct(values))) =>
        AttrF
          .apply[DataF, Fix[SchemaF], (Fix[SchemaF], Fix[DataF])](
            (structF, GStruct(values.map {
              case (name, value) => (name, (fields.apply(name), value))
            }))
          )
          .pure[M]
      case (arrayF @ Fix(ArrayF(elementSchema, _)), Fix(GArray(values))) =>
        AttrF
          .apply[DataF, Fix[SchemaF], (Fix[SchemaF], Fix[DataF])](arrayF, GArray(values.map { e =>
            elementSchema -> e
          }))
          .pure[M]
      case (schemaF, Fix(lower)) =>
        AttrF.apply[DataF, Fix[SchemaF], (Fix[SchemaF], Fix[DataF])](schemaF, lower.map((schemaF, _))).pure[M]
    }

  implicit val dataTraverse: Traverse[DataF] = new DefaultTraverse[DataF] {
    override def traverse[G[_]: Applicative, A, B](fa: DataF[A])(f: A => G[B]): G[DataF[B]] =
      fa.traverse(f)
  }

  implicit val dataFunctor: Functor[DataF] = new Functor[DataF] {
    override def map[A, B](fa: DataF[A])(f: A => B): DataF[B] = fa match {
      case GStruct(fields)  => GStruct[B](fields.mapValues(f))
      case GArray(elements) => GArray[B](elements.map(f))
      case GString(v)       => GString[B](v)
      case GBoolean(v)      => GBoolean[B](v)
      case GDouble(v)       => GDouble[B](v)
      case GFloat(v)        => GFloat[B](v)
      case GInt(v)          => GInt[B](v)
      case GLong(v)         => GLong[B](v)
      case GNull(t)          => GNull[B](t)
      case GDatetime(v)     => GDatetime[B](v)
      case GDate(v)         => GDate[B](v)
    }
  }
}
