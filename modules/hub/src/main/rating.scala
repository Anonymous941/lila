package lila.hub
package rating

opaque type PerfKey = String
object PerfKey extends OpaqueString[PerfKey]

opaque type PerfId = Int
object PerfId extends OpaqueInt[PerfId]

case class RatingRange(min: IntRating, max: IntRating):
  def contains(rating: IntRating) =
    (min <= RatingRange.min || rating >= min) &&
      (max >= RatingRange.max || rating <= max)
  override def toString = s"$min-$max"

object RatingRange:

  val min = IntRating(400)
  val max = IntRating(2900)

  val broad   = RatingRange(min, max)
  val default = broad

  def noneIfDefault(from: String): Option[RatingRange] =
    if from == default.toString then none
    else parse(from).filter(_ != default)

  private def readRating(str: String) = IntRating.from(str.toIntOption)

  // ^\d{3,4}\-\d{3,4}$
  def parse(from: String): Option[RatingRange] = for
    min <- readRating(from.takeWhile('-' !=))
    if acceptable(min)
    max <- readRating(from.dropWhile('-' !=).tail)
    if acceptable(max)
    if min < max
  yield RatingRange(min, max)

  def isValid(from: String): Boolean = parse(from).isDefined

  def orDefault(from: String) = parse(from) | default

  def acceptable(rating: IntRating) = broad.contains(rating)