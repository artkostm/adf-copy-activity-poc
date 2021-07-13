package io.github.artkostm.data.publishing.schema

import io.github.artkostm.data.schema.DataF._
import io.github.artkostm.data.schema.SchemaF._
import io.github.artkostm.data.schema.{DataF, SchemaF}
import higherkindness.droste.data.Fix
import higherkindness.droste.syntax.all.toFixSyntaxOps

object utils {
  val ReasonFieldName                 = "rejection_reason"
  val ReasonFieldSchema: Fix[SchemaF] = StringF(true).fix[SchemaF]

  def addReasonToRow(row: Fix[DataF], value: String): Fix[DataF] =
    row.put(ReasonFieldName, GString(value).fix[DataF])

  def addReasonToSchema(schema: Fix[SchemaF]): Fix[SchemaF] =
    schema.add(ReasonFieldName, ReasonFieldSchema)
}
