package experiments

import caps.assumeSafe
import scala.language.experimental.safe

// def parseInt(s: String): Int ? Err = …
// val (a, b) = ("123", "456")
// maybe:
//   parseInt(a)? + parseInt(b)?


def safeMain(using console: Consol3): Done =
  print_ln("leak")
  val issue = GitHub.login {
    GitHub.fetchIssue
  }
  // GitHub.login {
  //   print_ln("leak" + summon[GitHub.Secret]) // ILLEGAL!
  // }
  // val leaked = GitHub.login {
  //   summon[GitHub.Secret] // ILLEGAL!
  // }
  Done
