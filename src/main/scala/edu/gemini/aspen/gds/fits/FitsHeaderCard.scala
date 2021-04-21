package edu.gemini.aspen.gds.fits

import cats.data.Chain

sealed trait FitsHeaderCard {
  val keyword: String
  val comment: Option[String]
  def valueString: String

  def bytes: Chain[Byte] = {
    val kw          = keyword.padTo(8, ' ').take(8)
    val base        = s"$kw= $valueString"
    val withComment = comment.fold(base)(cmnt => s"$base / $cmnt")
    val fullStr     = withComment.padTo(80, ' ').take(80)

    Chain.fromSeq(fullStr.toList.map(_.toByte))
  }
  protected def padLeftTo(s: String, len: Int) = " ".repeat(len - s.length) + s
}

final case class BooleanHeaderCard(keyword: String, value: Boolean, comment: Option[String])
    extends FitsHeaderCard {
  val valueString = padLeftTo(if (value) "T" else "F", 20)
}

// Current GDS has a `format` in the config file. I need to determine how that
// is being used currently and if it is just for Doubles, etc.
final case class DoubleHeaderCard(
  keyword: String,
  value:   Double,
  comment: Option[String],
  format:  Option[String]
) extends FitsHeaderCard {
  val valueString = padLeftTo(format.fold(value.toString)(_.format(value)), 20)
}

final case class IntHeaderCard(keyword: String, value: Int, comment: Option[String])
    extends FitsHeaderCard {
  val valueString = padLeftTo(value.toString, 20)
}

final case class StringHeaderCard(keyword: String, value: String, comment: Option[String])
    extends FitsHeaderCard {
  def valueString = {
    val quoted = value.replace("'", "''")
    // final ' must be in or after column 20, but no later than 80,
    // and it starts in column 11. So the string itself must be between
    // 8 and 68 characters to account for the single quotes at each end.
    val inner  = quoted.padTo(8, ' ').take(68)
    s"'$inner'"
  }
}
