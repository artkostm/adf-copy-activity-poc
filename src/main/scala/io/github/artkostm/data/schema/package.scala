package io.github.artkostm.data

import higherkindness.droste.data.{AttrF, Fix}

package object schema {
  type EnvT[A] = AttrF[DataF, Fix[SchemaF], A]
}
