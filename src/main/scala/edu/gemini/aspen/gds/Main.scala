package edu.gemini.aspen.gds

import cats.syntax.all._
import cats.effect.{ Concurrent, IO, IOApp }
import edu.gemini.aspen.gds.fits._
import edu.gemini.aspen.gds.transfer._
import fs2.io.file.Files
import java.nio.file.Paths
import org.typelevel.log4cats.slf4j.Slf4jLogger

object Main extends IOApp.Simple {

  def run: IO[Unit] = {
    val required =
      Map(0  -> List("RA_UNIT", "DEC_UNIT", "CTYPE1", "CTYPE2", "NOT_ANYWHERE"),
          1  -> List("RA_UNIT", "CTYPE1", "MISSING"),
          10 -> List("Won't get here")
      )
    // val required = Map.empty[Int, List[String]]

    val additional: Map[Int, List[FitsHeaderCard]] =
      Map(
        0 -> List(BooleanHeaderCard("MY_BOOL", true, "some comment".some),
                  StringHeaderCard("MY_STRING", "0123456789" * 10, "won't fit".some)
        ),
        1 -> List(
          StringHeaderCard("SHORT", "S's", "Comment for string".some),
          IntHeaderCard("MY_INT", 2543, none),
          DoubleHeaderCard("MY_DBL", 0.000000123, "Is it exponential?".some, none)
        ),
        9 -> List(StringHeaderCard("TOO_HIGH", "Won't be included", none))
      )
    // val additional: Map[Int, List[FitsHeaderCard]] = Map.empty

    // val input  = Paths.get("/Users/tburnside/FITSfiles/README.txt")
    val input  = Paths.get("/Users/tburnside/FITSfiles/EUVEngc4151imgx.fits")
    // val input  = Paths.get("/Users/tburnside/FITSfiles/badheader.fits")
    val output = Paths.get("/Users/tburnside/FITSfiles/junk.fits")
    for {
      log <- Slf4jLogger.create[IO]
      _   <- TransferFitsFile.transfer(input,
                                       output,
                                       requiredHeaders = required,
                                       additionalHeaders = additional
             )(Concurrent[IO], Files[IO], log)
    } yield ()
  }
}
