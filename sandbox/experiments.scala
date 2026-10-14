package experiments

import caps.assumeSafe
import scala.language.experimental.safe

// def parseInt(s: String): Int ? Err = …
// val (a, b) = ("123", "456")
// maybe:
//   parseInt(a)? + parseInt(b)?


def safeMain(using console: Consol3): Unit =
  val issue = GitHub.session { secret =>
    GitHub.fetchIssue(secret)
  }
  print_ln(issue)
  // GitHub.session { secret =>
  //   print_ln("leak" + secret) // ILLEGAL!
  // }
  // val leaked = GitHub.session { secret =>
  //   secret // ILLEGAL!
  // }
