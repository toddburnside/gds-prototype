package edu.gemini.aspen.gds.transfer

import cats._
import cats.data._
import cats.effect.Concurrent
import cats.syntax.all._
import edu.gemini.aspen.gds.fits._
import fs2._
import fs2.io.file.Files
import java.nio.file._
import java.nio.file.Path
import org.typelevel.log4cats.Logger

object TransferFitsFile {

  val RecordLength      = 2880
  val HeaderLength      = 80
  val Space: Byte       = 32
  val MaxCardsPerHeader = 2000

  final case class HeaderRow(keyword: String, card: Chain[Byte])
  final case class ParserState(
    headerCount:   Int,
    currentHeader: Option[Chain[HeaderRow]],
    invalidFile:   Boolean
  )

  object ParserState {
    val empty = ParserState(0, None, false)
  }

  val simpleHeader             = "SIMPLE".toCharArray().map(_.toByte)
  val extensionHeader          = "XTENSION".toCharArray().map(_.toByte)
  val blankHeader: Chain[Byte] = Chain.fromSeq(List.fill(HeaderLength)(Space))
  val abandoned                = "The file will be copied but no further processing will be performed."

  private def matchesArray(chunk: Chunk[Byte], ray: Array[Byte]): Boolean =
    chunk.size >= ray.length &&
      ray.zipWithIndex.forall { case (b, idx) => b == chunk(idx) }

  private def isHeader(chunk:     Chunk[Byte]): Boolean =
    matchesArray(chunk, simpleHeader) || matchesArray(chunk, extensionHeader)

  private def extractKeyword(chunk: Chunk[Byte]): String =
    chunk.take(8).toList.map(_.toChar).mkString.trim()

  private def headerRecords(headers: Chain[HeaderRow]): Chunk[Byte] = {
    val actualHeaders: Chain[Byte] = headers.flatMap(_.card)
    val overflow                   = actualHeaders.length % RecordLength
    val extra                      =
      if (overflow > 0) Chain.fromSeq(List.fill(RecordLength - overflow.toInt)(Space))
      else Chain.empty[Byte]
    Chunk.chain(actualHeaders ++ extra)
  }

  private def parseHeaderChunk(chunk: Chunk[Byte]): (Chain[HeaderRow], Boolean) = {
    def loop(c: Chunk[Byte], hs: Chain[HeaderRow]): (Chain[HeaderRow], Boolean) =
      if (c.isEmpty) (hs, false)
      else {
        val keyword     = extractKeyword(c)
        val (hdr, rest) = c.splitAt(HeaderLength)
        val newHs       = hs :+ HeaderRow(keyword, hdr.toChain)
        if (keyword == "END") (newHs, true)
        else {
          loop(rest, newHs)
        }
      }
    loop(chunk, Chain.empty)
  }

  private def validateHeaders[F[_]](
    headerNumber: Int,
    headers:      Chain[HeaderRow],
    required:     Map[Int, List[String]]
  ): ValidatedNec[String, Unit] =
    required.get(headerNumber).fold(().validNec[String]) { requiredKeyWords =>
      val keywords = headers.map(_.keyword)
      requiredKeyWords
        .map(key => if (keywords.contains(key)) ().validNec else key.invalidNec)
        .sequence
        .void
        .leftMap(es =>
          NonEmptyChain(s"Header $headerNumber missing keywords: ${es.mkString_(", ")}")
        )
    }

  private def addHeaders[F[_]](
    headerNumber: Int,
    headers:      Chain[HeaderRow],
    additional:   Map[Int, List[FitsHeaderCard]]
  ): Chain[HeaderRow] =
    additional.get(headerNumber).fold(headers) { newHeaders =>
      // Chain doesn't have `splitAt`. Maybe it's not the best data structure to use here.
      val asList      = headers.toList
      val (head, end) = asList.splitAt(asList.length - 1)
      val newRows     = newHeaders.map(h => HeaderRow(h.keyword, h.bytes))
      Chain.fromSeq(head) ++ Chain.fromSeq(newRows) ++ Chain.fromSeq(end)
    }

  private def fitsPipe[F[_]: Applicative: Logger](
    required:   Map[Int, List[String]],
    additional: Map[Int, List[FitsHeaderCard]]
  ): Pipe[F, Byte, Byte] = {
    def validate(pred: => Boolean, errorMsg: String): ValidatedNec[String, Unit] =
      if (pred) ().validNec else errorMsg.invalidNec

    def logOnError[A](valid: ValidatedNec[String, A]) =
      valid.fold(_.map(e => Logger[F].error(e)).sequence.void, _ => Applicative[F].unit)

    def drainStateHeaders(state: ParserState) = state.currentHeader match {
      case Some(hs) => outputHeaders(hs)
      case None     => Pull.done
    }

    def outputHeaders(headers: Chain[HeaderRow]) = Pull.output(headerRecords(headers))

    def go(s: Stream[F, Byte], state: ParserState): Pull[F, Byte, Unit] =
      s.pull.unconsN(RecordLength, true).flatMap {
        case Some((hd, tl)) =>
          if (state.invalidFile)
            Pull.output(hd) >> go(tl, state)
          else if (hd.size != RecordLength)
            // this should only happen at the end of the stream
            Pull.eval(
              Logger[F].error(s"Invalid FITS file - incorrect record size. $abandoned")
            ) >> drainStateHeaders(state) >>
              Pull.output(hd) >> go(tl, state.copy(invalidFile = true))
          else {
            if (isHeader(hd) || state.currentHeader.nonEmpty) {
              val (newHeaders, done) = parseHeaderChunk(hd)
              val allHeaders         = state.currentHeader.fold(newHeaders)(_ ++ newHeaders)
              if (done) {
                val missingHeaderLogging =
                  logOnError(validateHeaders(state.headerCount, allHeaders, required))
                val withAdditional       = addHeaders(state.headerCount, allHeaders, additional)
                val newState             = state.copy(headerCount = state.headerCount + 1, None)
                Pull.eval(missingHeaderLogging) >> outputHeaders(withAdditional) >> go(tl, newState)
              } else if (allHeaders.length > MaxCardsPerHeader)
                // make sure we don't read in a whole file if a header doesn't end with an "END"
                Pull.eval(
                  Logger[F].error(
                    s"Header ${state.headerCount} exceeded $MaxCardsPerHeader cards. $abandoned"
                  )
                ) >> outputHeaders(allHeaders) >> go(
                  tl,
                  state.copy(currentHeader = none, invalidFile = true)
                )
              else go(tl, state.copy(currentHeader = allHeaders.some))
            } else { // not in a header
              if (state.headerCount == 0)
                Pull.eval(
                  Logger[F].error(s"Invalid FITS file - no initial header. $abandoned")
                ) >> Pull.output(hd) >> go(tl, state.copy(invalidFile = true))
              else Pull.output(hd) >> go(tl, state)
            }
          }
        case None           =>
          val nonTermValid  =
            validate(state.currentHeader.isEmpty,
                     s"Invalid FITS file - header ${state.headerCount} did not terminate."
            )
          val maxRequired   = required.keySet.fold(0)(Math.max) + 1
          val reqCountValid = validate(
            maxRequired <= state.headerCount,
            s"Only found ${state.headerCount} headers but config has required headers for $maxRequired"
          )
          val maxAdditional = additional.keySet.fold(0)(Math.max) + 1
          val addCountValid = validate(
            maxAdditional <= state.headerCount,
            s"Only found ${state.headerCount} headers but config has additional headers for $maxAdditional"
          )
          val errorF        = logOnError((nonTermValid, reqCountValid, addCountValid).tupled)

          Pull.eval(errorF) >> drainStateHeaders(state)
      }
    in => go(in, ParserState.empty).stream
  }

  def stream[F[_]: Concurrent: Files: Logger](
    input:             Stream[F, Byte],
    requiredHeaders:   Map[Int, List[String]],
    additionalHeaders: Map[Int, List[FitsHeaderCard]]
  ): Stream[F, Byte] =
    input.through(fitsPipe(requiredHeaders, additionalHeaders))

  def transfer[F[_]: Concurrent: Files: Logger](
    input:             Path,
    output:            Path,
    requiredHeaders:   Map[Int, List[String]],
    additionalHeaders: Map[Int, List[FitsHeaderCard]]
  ): F[Unit] =
    stream(
      Files[F]
        .readAll(input, chunkSize = RecordLength),
      requiredHeaders,
      additionalHeaders
    )
      .through(Files[F].writeAll(output, List(StandardOpenOption.TRUNCATE_EXISTING)))
      .compile
      .drain
}
