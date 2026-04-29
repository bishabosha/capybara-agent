package capybara.agent

import dotty.tools.repl.ReplDriver
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Paths
import java.io.PrintStream
import dotty.tools.repl.State
import steps.result.Result, Result.eval.{raise, ok, check}
import steps.result.Result.apply as result
import steps.result.Result.task as task
import dotty.tools.dotc.reporting.Diagnostic
import scala.util.boundary.Label
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

object ReplExec {
  type ResultBody[+T, +E] = Label[Result.Err[E]] ?=> T

  enum ScalaToolResult:
    case Success(value: ujson.Value)
    case Failure(error: String)

    def encodeAsJson: String = this match
      case Success(value) => ujson.write(ujson.Obj("value" -> value))
      case Failure(error) => ujson.write(ujson.Obj("error" -> error))

  private object lock

  private object ScalaClassLoader
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
  private class Session(ps: PrintStream)
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
      val msg = embedString(message.msg.toString)
      s"""- at ${message.pos.line + 1}:${message.pos.column + 1}: $msg
        |""".stripMargin

    def step(command: String, label: String): Result[String, String] = result {
      runWithState(command)
      val output = ReplOutputStream.flushBuffer()
      if state.context.reporter.hasErrors then raise(s"""${embedString(label)}
          |
          |Errors summarized:
          |${embedString(state.context.reporter.allErrors.map(renderMessage).mkString("\n"))}
          |
          |REPL output:
          |```scala
          |${embedString(output)}
          |```
          |""".stripMargin)
      else output
    }

    def embedString(str: String) = str.linesIterator.mkString("\n|")

    def loadJar(skill: Skill): Result[Unit, String] = task {
      val jarRelPath = os.rel / ".agent-runtime" / "interface" / skill.universe / "impl.jar"
      val jarPath = os.pwd / jarRelPath
      runWithState(s":jar $jarPath")
      val jarOutput = ReplOutputStream.flushBuffer()
      val cpError: PartialFunction[String, Unit] = { case s"""Cannot add "$_" to classpath.""" => }
      if jarOutput.linesIterator.collectFirst(cpError).isDefined then
        raise(
          s"""Failed to load implementation JAR for universe `${skill.universe}` at path: $jarRelPath
            |This is an issue with the Skill definition or with the agent's ability to write to the filesystem.
            |Please check that the skill is defined correctly and that the agent has permission to write to its working directory.
            |REPL output:
            |```scala
            |${embedString(jarOutput.replaceAllLiterally(jarPath.toString, jarRelPath.toString))}
            |```
            |""".stripMargin
        )
    }

    def loadPredef(skill: Skill): Result[Unit, String] = task {
      val _ = step(
        skill.predef,
        s"""Could not execute scaffolding code for universe `${skill.universe}`, given by the code:
          |```scala
          |${embedString(skill.predef)}
          |```
          |This is an issue with the Skill definition. Please tell the maintainers of the skill to fix the issue.
          |""".stripMargin
      ).ok
    }

    def runExpression(builtins: Skill, skill: Skill, scalaCode: String): Result[String, String] =
      stateless {
        val uuid = java.util.UUID.randomUUID().toString.replaceAll("-", "_")
        val methodName = s"agentCode_$uuid"
        result {
          loadJar(builtins).check
          loadPredef(builtins).check
          loadJar(skill).check
          loadPredef(skill).check
          step(
            s"""def $methodName() = {
            |${embedString(scalaCode)}
            |}
            |""".stripMargin,
            s"while compiling agent generated code for universe `${skill.universe}`."
          ).ok
          step(
            s"""builtins.builtinPrintAndFormatData($methodName())""",
            s"while running agent generated code for universe `${skill.universe}`."
          ).ok
        }
      }

    def stateless[T](op: => T): T = lock.synchronized {
      try op
      finally
        resetToInitial()
        state = initialState
        ReplOutputStream.clearBuffer()
    }
  }

  private object ReplOutputStream extends java.io.OutputStream {
    private val buffer = new StringBuilder

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

  private val globalSession = new Session(new PrintStream(ReplOutputStream))

  def runCode(builtins: Skill, skill: Skill, scalaCode: String): Result[String, String] = try {
    globalSession.runExpression(builtins, skill, scalaCode)
  } finally {
    ReplOutputStream.clearBuffer()
  }

  def runCodeHarness(builtins: Skill, skill: Skill, scalaCode: String, callId: Int, log: Logger)(
      using ExecutionContext
  ): Future[ScalaToolResult] =
    def strippedAnsi(str: String): String =
      str.replaceAll("\u001B\\[[;\\d]*m", "")
    Future[Result[String, String]] {
      scala.concurrent.blocking {
        runCode(builtins, skill, scalaCode)
      }
    }.transform({ try0 =>
      val normalized = try0 match
        case scala.util.Success(res) =>
          res match
            case Result.Ok(value) =>
              try
                val data = ujson.read(strippedAnsi(value))
                ScalaToolResult.Success(data)
              catch
                case err: Exception =>
                  ScalaToolResult.Failure(
                    s"Failed to parse output as JSON: ${strippedAnsi(value)}. Error: ${err.getMessage}"
                  )
            case Result.Err(error) =>
              ScalaToolResult.Failure(strippedAnsi(error))
        case scala.util.Failure(exception) =>
          ScalaToolResult.Failure(
            s"Exception while executing code: ${strippedAnsi(exception.getMessage)}"
          )
      locally {
        normalized.match
          case ScalaToolResult.Success(value) =>
            log.print(
              s"${Console.GREEN}Result for code chunk [$callId]:\n---\n${value}${Console.RESET}\n"
            )
          case ScalaToolResult.Failure(error) =>
            log.print(
              s"${Console.RED}Error executing code chunk [$callId]:\n---\n${error}${Console.RESET}\n"
            )
      }
      scala.util.Success(normalized)
    })

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
      val tempDir = os.pwd / ".agent-runtime" / "temp" / skill.name
      try
        os.makeDir.all(tempDir)
        withTempFile(tempDir, "Interface.scala") { tempInterface =>
          os.write.over(tempInterface, skill.interface)
          withTempFile(tempDir, "sigs.jar") { sigsJar =>
            os.proc(
              Seq[os.Shellable](
                "scala",
                "--power",
                "package",
                "--library",
                "-f",
                "-o",
                sigsJar,
                "-language:experimental.safe",
                tempInterface
              )
            ).call()
            os.copy(sigsJar, targetSigJar, replaceExisting = true)
            withTempFile(tempDir, "Implementation.scala") { tempCode =>
              os.write.over(tempCode, skill.code)
              withTempFile(tempDir, "impl.jar") { implJar =>
                os.proc(
                  Seq[os.Shellable](
                    "scala",
                    "--power",
                    "package",
                    "--library",
                    "-f",
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
