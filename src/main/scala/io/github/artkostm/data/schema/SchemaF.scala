package io.github.artkostm.data.schema

import cats.implicits._
import cats.{Applicative, Functor}
import io.github.artkostm.data.schema.SchemaF._
import higherkindness.droste.data.Fix
import higherkindness.droste.syntax.all.toFixSyntaxOps

import java.sql.{Date, Timestamp}
import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.language.higherKinds

sealed trait SchemaF[A] {

  def traverse[F[_]: Applicative, B](f: A => F[B]): F[SchemaF[B]] = this match {
    case StructF(fields, n) =>
      fields
        .foldRight(Map.empty[String, B].pure[F]) {
          case ((name, v), acc) =>
            (name.pure[F], f(v), acc).mapN { (n, a, m) =>
              m + (n -> a)
            }
        }
        .map(StructF(_, n))
    case ArrayF(elem, n)   => f(elem).map(ArrayF.apply(_, n))
    case DecimalF(p, s, n) => (DecimalF[B](p, s, n): SchemaF[B]).pure[F]
    case BooleanF(n)       => (BooleanF[B](n): SchemaF[B]).pure[F]
    case DoubleF(n)        => (DoubleF[B](n): SchemaF[B]).pure[F]
    case FloatF(n)         => (FloatF[B](n): SchemaF[B]).pure[F]
    case StringF(n)        => (StringF[B](n): SchemaF[B]).pure[F]
    case LongF(n)          => (LongF[B](n): SchemaF[B]).pure[F]
    case IntF(n)           => (IntF[B](n): SchemaF[B]).pure[F]
    case ByteF(n)          => (ByteF[B](n): SchemaF[B]).pure[F]
    case ShortF(n)         => (ShortF[B](n): SchemaF[B]).pure[F]
    case DatetimeF(n, f)   => (DatetimeF[B](n, f): SchemaF[B]).pure[F]
    case DateF(n)          => (DateF[B](n): SchemaF[B]).pure[F]
  }
}

sealed trait ValueF[A, B] extends SchemaF[A] {
  val nullable: Boolean
}

object SchemaF {
  implicit class FixedSchema(s: Fix[SchemaF]) {
    def add(name: String, child: Fix[SchemaF]): Fix[SchemaF] = s match {
      case Fix(ss: StructF[Fix[SchemaF]]) => ss.add(name, child).fix[SchemaF]
      case x => throw new UnsupportedOperationException(s"Cannot add schema $child to $x")
    }
  }

  final case class StructF[A](fields: Map[String, A], nullable: Boolean) extends SchemaF[A] {
    def add(name: String, value: A): SchemaF[A] = StructF[A](fields + (name -> value), nullable)
  }
  final case class ArrayF[A](element: A, nullable: Boolean)                   extends SchemaF[A]
  final case class StringF[A](nullable: Boolean)                              extends ValueF[A, String]
  final case class DecimalF[A](precision: Int, scale: Int, nullable: Boolean) extends ValueF[A, BigDecimal]
  final case class BooleanF[A](nullable: Boolean)                             extends ValueF[A, Boolean]
  final case class DoubleF[A](nullable: Boolean)                              extends ValueF[A, Double]
  final case class FloatF[A](nullable: Boolean)                               extends ValueF[A, Float]
  final case class LongF[A](nullable: Boolean)                                extends ValueF[A, Long]
  final case class IntF[A](nullable: Boolean)                                 extends ValueF[A, Int]
  final case class ByteF[A](nullable: Boolean)                                extends ValueF[A, Byte]
  final case class ShortF[A](nullable: Boolean)                               extends ValueF[A, Short]
  final case class DatetimeF[A](nullable: Boolean, format: DatetimeF.Format)  extends ValueF[A, Long]
  final case class DateF[A](nullable: Boolean)                                extends ValueF[A, Int]

  object DatetimeF {
    sealed trait Format
    case object TimestampMicros extends Format

    def toTs(micros: Long, format: Format): Timestamp = format match {
      case TimestampMicros => Timestamp.from(Instant.EPOCH.plus(micros, ChronoUnit.MICROS))
    }

    def toDate(days: Int): Date = new Date(Instant.EPOCH.plus(days, ChronoUnit.DAYS).toEpochMilli)
  }

  val emptyStruct: Fix[SchemaF] = StructF(Map.empty, true).fix[SchemaF]

  def isArray(f: Fix[SchemaF]): Boolean = Fix.un(f) match {
    case ArrayF(_, _) => true
    case _         => false
  }

  def isNullableField(s: Fix[SchemaF]): Boolean = Fix.un(s) match {
    case v: ValueF[_, _] => v.nullable
    case _               => false
  }

  def getHigherLevelFieldType(responseSchema: Fix[SchemaF]): Option[(String, Fix[SchemaF])] =
    Fix.un(responseSchema) match {
      case StructF(fields, _) =>
        fields.toList match {
          case (name, Fix(ArrayF(s @ Fix(StructF(_, _)), _))) :: Nil => Some(name -> s)
          case _                                               => None
        }
    }

  implicit val schemaFunctor: Functor[SchemaF] = new Functor[SchemaF] {
    override def map[A, B](fa: SchemaF[A])(f: A => B): SchemaF[B] = fa match {
      case StructF(fields, n) => StructF(fields.mapValues(f), n)
      case ArrayF(elem, n)    => ArrayF(f(elem), n)
      case DecimalF(p, s, n)  => DecimalF[B](p, s, n)
      case BooleanF(n)        => BooleanF[B](n)
      case DoubleF(n)         => DoubleF[B](n)
      case FloatF(n)          => FloatF[B](n)
      case StringF(n)         => StringF[B](n)
      case LongF(n)           => LongF[B](n)
      case IntF(n)            => IntF[B](n)
      case ByteF(n)           => ByteF[B](n)
      case ShortF(n)          => ShortF[B](n)
      case DatetimeF(n, f)    => DatetimeF[B](n, f)
      case DateF(n)           => DateF[B](n)
    }
  }

//  implicit val schemaTraverse: Traverse[SchemaF] = new DefaultTraverse[SchemaF] {
//    override def traverse[G[_]: Applicative, A, B](fa: SchemaF[A])(f: A => G[B]): G[SchemaF[B]] =
//      fa.traverse(f)
//  }
}
