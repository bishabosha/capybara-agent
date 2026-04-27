package capybara.agent

import dotty.tools.repl.ReplDriver
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Paths
import java.io.PrintStream
import dotty.tools.repl.State
import steps.result.Result
import dotty.tools.dotc.reporting.Diagnostic

object ReplExec {

  object ScalaClassLoader
      extends java.net.URLClassLoader(
        {
          sys
            .props("java.class.path")
            .split(java.io.File.pathSeparator)
            .filter(p =>
              p.contains("/org/scala-lang/scala-library/")
                || p.contains("/org/scala-lang/scala3-library_3/")
            )
            .map(path => Paths.get(path).toUri.toURL)
        },
        ClassLoader.getSystemClassLoader.getParent
      )

  /** For now - no plans to cross-universe classpaths */
  class Session(ps: PrintStream)
      extends ReplDriver(
        settings = Array(
          "-classpath",
          ScalaClassLoader.getURLs.map(_.toURI().getPath()).mkString(":"),
          "-language:experimental.safe",
          "-color:never"
        ),
        out = ps,
        classLoader = Some(ScalaClassLoader),
        extraPredef = ""
      ) {

    var state = initialState

    def runWithState(code: String): Unit =
      given State = state
      state = run(code)

    def renderMessage(message: Diagnostic): String =
      s"at ${message.pos.line + 1}:${message.pos.column + 1}: ${message.msg}"

    def runExpression(skill: Skill, scalaCode: String): Result[String, String] =
      val resolvedLib = os.pwd / ".agent-runtime" / "interface" / skill.universe / "impl.jar"
      if os.exists(resolvedLib) then
        runWithState(s":jar $resolvedLib")
        runWithState(s"""def __the_code__ = {
          |${skill.predef}
          |$scalaCode
          |}""".stripMargin)
        val preamble = MyBufferedOutputStream.flushBuffer()
        runWithState("__the_code__")
        val result = MyBufferedOutputStream.flushBuffer()
        val ret =
          if state.context.reporter.hasErrors then
            Result.Err(
              state.context.reporter.allErrors.map(renderMessage).mkString("\n") + "\n" + preamble
            )
          else Result.Ok(result) // todo: handle preamble if error in compilation, etc
        runWithState(":reset")
        ret
      else Result.Err(s"Universe ${skill.universe} not found")
  }

  object MyBufferedOutputStream extends java.io.OutputStream {
    private val buffer = new StringBuilder
    private val lock = new Object

    def flushBuffer(): String =
      lock.synchronized {
        val content = buffer.toString()
        buffer.clear()
        content
      }

    def clearBuffer(): Unit =
      lock.synchronized {
        buffer.clear()
      }

    override def write(b: Int): Unit =
      lock.synchronized {
        buffer.append(b.toChar)
      }
  }

  val globalSession = new Session(new PrintStream(MyBufferedOutputStream))

  def runCode(skill: Skill, scalaCode: String): Result[String, String] = try {
    globalSession.runExpression(skill, scalaCode)
  } finally {
    MyBufferedOutputStream.clearBuffer()
  }

  def compileSkill(skill: Skill): os.Path = {
    def digestFrom(strings: String*): String = {
      val hasher = java.security.MessageDigest.getInstance("SHA-256")
      for sourceCode <- strings.map(_.getBytes()) do hasher.update(sourceCode)
      val digest = hasher.digest()
      val base64 = java.util.Base64.getEncoder.encodeToString(digest)
      base64
    }
    def withTempFile[T](prefix: os.Path, name: String)(f: os.Path => T): T = {
      val tempFile = prefix / name
      try f(tempFile)
      finally os.remove(tempFile)
    }
    val outdir = os.pwd / ".agent-runtime" / "interface" / skill.name
    os.makeDir.all(outdir)
    val targetSigJar = outdir / "sigs.jar"
    val targetImplJar = outdir / "impl.jar"
    val existingSigs = scala.util.Try(os.read(outdir / "sigs.hash"))
    val digest = digestFrom(skill.interface, skill.code, skill.api, skill.predef)
    val shouldWrite = !os.exists(targetSigJar) || !os
      .exists(targetImplJar) || existingSigs.map(_ != digest).getOrElse(true)
    if (shouldWrite) {
      os.write.over(outdir / "sigs.hash", digest)
      val tempDir = os.pwd / ".agent-runtime/temp"
      try
        os.makeDir.all(tempDir)
        withTempFile(tempDir, "Interface.scala") { tempInterface =>
          os.write(tempInterface, skill.interface)
          withTempFile(tempDir, "sigs.jar") { sigsJar =>
            os.proc(
              Seq[os.Shellable](
                "scala",
                "--power",
                "package",
                "--library",
                "-o",
                sigsJar,
                "-language:experimental.safe",
                tempInterface
              )
            ).call()
            os.copy(sigsJar, targetSigJar, replaceExisting = true)
            withTempFile(tempDir, "Implementation.scala") { tempCode =>
              os.write(tempCode, skill.code)
              withTempFile(tempDir, "impl.jar") { implJar =>
                os.proc(
                  Seq[os.Shellable](
                    "scala",
                    "--power",
                    "package",
                    "--library",
                    "-o",
                    implJar,
                    "--extra-jar",
                    sigsJar,
                    tempCode
                  )
                ).call()
                os.copy(implJar, targetImplJar, replaceExisting = true)
              }
            }
          }
        }
      finally os.remove.all(tempDir)
    }
    outdir
  }
}
