package capybara.agent

import dotty.tools.repl.ReplDriver
import dotty.tools.repl.ReplBytecodeInstrumentation.setStopFlag
import java.nio.file.Paths
import java.io.{PrintStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import dotty.tools.repl.{State, ParseResult, SyntaxErrors, ReplCompiler}
import steps.result.Result, Result.eval.{raise, ok, check}
import steps.result.Result.apply as result
import steps.result.Result.task as task
import dotty.tools.dotc.reporting.Diagnostic
import dotty.tools.dotc.core.Contexts.FreshContext
import dotty.tools.dotc.core.NameOps.*
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

object ReplExec {
  val ScalaVersion = dotty.tools.dotc.config.Properties.versionNumberString

  case class CodeOutput(stdout: String, stderr: String, failed: Boolean = false)

  enum ScalaToolResult:
    case Success(stdout: Vector[ujson.Value], stderr: Vector[ujson.Value])
    case Failure(error: ujson.Value, stdout: Vector[ujson.Value] = Vector.empty, stderr: Vector[ujson.Value] = Vector.empty)
    case NoOutput

    def encodeAsJson: String = this match
      case Success(stdout, stderr) =>
        ujson.write(ujson.Obj("success" -> true, "stdout" -> ujson.Arr.from(stdout), "stderr" -> ujson.Arr.from(stderr)))
      case Failure(error, stdout, stderr) =>
        ujson.write(ujson.Obj("executionError" -> error, "stdout" -> ujson.Arr.from(stdout), "stderr" -> ujson.Arr.from(stderr)))
      case NoOutput => ujson.write(ujson.Obj("success" -> true, "stdout" -> ujson.Arr(), "stderr" -> ujson.Arr()))

  /** Capture exact bytes for the model while publishing complete UTF-8 records immediately. */
  private class StreamCapture(onLine: String => Unit):
    private val bytes = new ByteArrayOutputStream()
    private val pending = new ByteArrayOutputStream()
    private def publish(): Unit =
      if pending.size() > 0 then
        val line = pending.toString(UTF_8)
        pending.reset()
        try onLine(line)
        catch case scala.util.control.NonFatal(_) => ()
    private val sink = new java.io.OutputStream:
      override def write(value: Int): Unit = StreamCapture.this.synchronized {
        bytes.write(value)
        pending.write(value)
        if value == '\n'.toInt then publish()
      }
      override def write(data: Array[Byte], offset: Int, length: Int): Unit =
        StreamCapture.this.synchronized {
          bytes.write(data, offset, length)
          var index = offset
          while index < offset + length do
            pending.write(data(index))
            if data(index) == '\n'.toByte then publish()
            index += 1
        }
      override def close(): Unit = StreamCapture.this.synchronized { publish() }
    val stream = new PrintStream(sink, true, UTF_8)
    def content: String = this.synchronized {
      stream.flush()
      publish()
      bytes.toString(UTF_8)
    }

  private object lock
  private object compileLock
  private final class ActiveReplExecution(val thread: Thread):
    private val classLoaders = ConcurrentLinkedQueue[ClassLoader]()

    def addClassLoader(classLoader: ClassLoader): Unit =
      if !classLoaders.contains(classLoader) then classLoaders.add(classLoader)

    def foreachClassLoader(op: ClassLoader => Unit): Unit =
      val iterator = classLoaders.iterator()
      while iterator.hasNext do op(iterator.next())

  private final case class TrackedReplExecution(
      execution: ActiveReplExecution,
      closeable: AutoCloseable
  ) extends AutoCloseable:
    def close(): Unit = closeable.close()

  private object ScalaClassLoader
      extends java.net.URLClassLoader(
        {
          sys
            .props("java.class.path")
            .split(java.io.File.pathSeparator)
            .filter(p => p.contains("/org/scala-lang/scala-library/"))
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
          "-language:experimental.captureChecking",
          "-Xrepl-interrupt-instrumentation:true",
          "-color:never"
        ),
        out = ps,
        classLoader = Some(ScalaClassLoader),
        extraPredef = ""
      ) {

    // Diagnostics already use the driver's `out`; tool streams are passed explicitly.
    override protected def redirectOutput: Boolean = false

    var state = initialState.copy(quiet = true)
    // initialState retains the driver's root context, used to start every new run.
    private var modeContext = state.context.asInstanceOf[FreshContext]
    private var lastRunFailed = false
    private val activeExecution = AtomicReference[ActiveReplExecution | Null](null)

    override protected def interpret(parsed: ParseResult)(using previous: State): State =
      val next = super.interpret(parsed)
      // Failed compilations drain the reporter and retain the previous context.
      // The returned state marks their wrapper invalid; syntax errors have no wrapper.
      lastRunFailed ||= parsed.isInstanceOf[SyntaxErrors] ||
        (next.invalidObjectIndexes -- previous.invalidObjectIndexes).nonEmpty
      next

    private def currentReplClassLoader(using state: State): ClassLoader =
      rendering.getClass
        .getMethods()
        .find(method => method.getName == "classLoader" && method.getParameterCount == 1)
        .map(_.invoke(rendering, state.context).asInstanceOf[ClassLoader])
        .getOrElse(throw IllegalStateException("Could not access REPL class loader"))

    private def invokeEntryPoint(name: String, stdout: PrintStream, stderr: PrintStream): Boolean =
      given State = state
      val loader = currentReplClassLoader
      val wrapper = ReplCompiler.objectNames(state.objectIndex).encode.toString
      val entry = Class.forName(wrapper, true, loader)
        .getMethod(name, classOf[PrintStream], classOf[PrintStream])
      val thread = Thread.currentThread()
      val previousLoader = thread.getContextClassLoader
      try
        thread.setContextClassLoader(loader)
        entry.invoke(null, stdout, stderr).asInstanceOf[java.lang.Boolean].booleanValue()
      catch
        case error: java.lang.reflect.InvocationTargetException => throw error.getCause
      finally thread.setContextClassLoader(previousLoader)

    private def interruptExecution(execution: ActiveReplExecution): Unit =
      execution.foreachClassLoader { classLoader =>
        setStopFlag(classLoader, b = true)
      }
      execution.thread.interrupt()

    def interruptActiveExecution(): Boolean =
      activeExecution.get() match
        case null      => false
        case execution =>
          interruptExecution(execution)
          true

    private def trackExecution(cancellationToken: CancellationToken): TrackedReplExecution =
      given State = state
      val execution = ActiveReplExecution(Thread.currentThread())
      registerCurrentClassLoader(execution)
      activeExecution.set(execution)
      val cancellationRegistration = cancellationToken.onCancel {
        interruptExecution(execution)
      }
      TrackedReplExecution(
        execution,
        new AutoCloseable:
          def close(): Unit =
            cancellationRegistration.close()
            val _ = activeExecution.compareAndSet(execution, null)
            execution.foreachClassLoader { classLoader =>
              setStopFlag(classLoader, b = false)
            }
            if Thread.currentThread() == execution.thread then
              val _ = Thread.interrupted()
            ()
      )

    private def registerCurrentClassLoader(execution: ActiveReplExecution): Unit =
      given State = state
      val classLoader = currentReplClassLoader
      setStopFlag(classLoader, b = false)
      execution.addClassLoader(classLoader)

    def runWithState(code: String): Unit =
      given State = state
      lastRunFailed = false
      state = run(code)

    /** Only harness scaffolding is compiled in trusted mode; snippet bodies stay safe. */
    private def setSafeMode(enabled: Boolean): Unit =
      val language = if enabled then "experimental.safe" else "experimental.captureChecking"
      // :settings appends language features, so it cannot turn safe mode back off.
      val _ = modeContext.setSetting(modeContext.settings.language, Nil)
      runWithState(s":settings -language:$language")
      modeContext = state.context.asInstanceOf[FreshContext]
      ReplOutputStream.clearBuffer()

    def renderMessage(message: Diagnostic): String =
      val msg = embedString(message.msg.toString)
      s"""- at ${message.pos.line + 1}:${message.pos.column + 1}: $msg
        |""".stripMargin

    def step(command: String, label: String): Result[String, String] = result {
      val execResult = Result.catchException({ case e =>
        s"""${embedString(label)}
          |
          |Errors summarized:
          |$e
          |${embedString(state.context.reporter.allErrors.map(renderMessage).mkString("\n"))}
          |""".stripMargin
      })({ runWithState(command) })
      execResult.check
      val output = ReplOutputStream.flushBuffer()
      if lastRunFailed || state.context.reporter.hasErrors then raise(s"""${embedString(label)}
          |
          |REPL output:
          |```scala
          |${embedString(output)}
          |```
          |""".stripMargin)
      else output
    }

    def embedString(str: String) = str.linesIterator.mkString("\n|")

    def loadJar(jarRelPath: os.RelPath, skill: Skill): Result[Unit, String] = task {
      val jarPath = os.pwd / jarRelPath
      runWithState(s":jar $jarPath")
      val jarOutput = ReplOutputStream.flushBuffer()
      val cpError: PartialFunction[String, Unit] = {
        case s"""Cannot add "$_" to classpath."""        =>
        case s"""The path '$_' cannot be loaded$_"""     =>
        case s"""Failed to load '$_' to classpath: $_""" =>
      }
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
      skill.predef match
        case Some(predef) =>
          val _ = step(
            predef,
            s"""Could not execute scaffolding code for universe `${skill.universe}`, given by the code:
              |```scala
              |${embedString(predef)}
              |```
              |This is an issue with the Skill definition. Please tell the maintainers of the skill to fix the issue.
              |""".stripMargin
          ).ok
        case _ => ()
    }

    def loadSkill(skill: Skill): Result[Unit, String] = task {
      if !skill.requiresRuntimeClasspath then ()
      else {
        val sigsRelPath = os.rel / ".agent-runtime" / "interface" / skill.universe / "sigs.jar"
        val implRelPath = os.rel / ".agent-runtime" / "interface" / skill.universe / "impl.jar"
        loadJar(sigsRelPath, skill).check
        loadJar(implRelPath, skill).check
        loadPredef(skill).check
      }
    }

    def runExpression(
        builtins: Skill,
        skills: Seq[Skill],
        apiSkills: Seq[Skill],
        scalaCode: String,
        cancellationToken: CancellationToken,
        onOutput: (ToolOutputChannel, String) => Unit
    ): Result[CodeOutput, String] =
      if cancellationToken.isCancelled then Result.Err("cancelled")
      else
        stateless {
          val uuid = java.util.UUID.randomUUID().toString.replaceAll("-", "_")
          val methodName = s"agentCode_$uuid"
          val entryName = s"harnessEntry_$uuid"
          val stdout = new StreamCapture(line => onOutput(ToolOutputChannel.Stdout, line))
          val stderr = new StreamCapture(line => onOutput(ToolOutputChannel.Stderr, line))
          val trackedExecution = trackExecution(cancellationToken)
          try
            result {
              loadSkill(builtins).check
              apiSkills.foreach(selected =>
                loadJar(os.rel / ".agent-runtime/interface" / selected.universe / "sigs.jar", selected).check)
              skills.foreach { selected =>
                loadJar(os.rel / ".agent-runtime/interface" / selected.universe / "impl.jar", selected).check
                loadPredef(selected).check
              }
              val bindings = SkillScaffold.bindings(builtins, skills)
              registerCurrentClassLoader(trackedExecution.execution)
              if cancellationToken.isCancelled then raise("cancelled")
              setSafeMode(enabled = true)
              val _ = step(
                SkillScaffold.definition(methodName, bindings, scalaCode),
                s"while compiling code for skills `${skills.map(_.universe).mkString(",")}`."
              ).ok
              if cancellationToken.isCancelled then raise("cancelled")
              setSafeMode(enabled = false)
              val _ = step(
                SkillScaffold.entryPoint(entryName, methodName, bindings),
                s"while compiling trusted wiring for skills `${skills.map(_.universe).mkString(",")}`."
              ).ok
              registerCurrentClassLoader(trackedExecution.execution)
              val succeeded = invokeEntryPoint(entryName, stdout.stream, stderr.stream)
              if cancellationToken.isCancelled then raise("cancelled")
              CodeOutput(stdout.content, stderr.content, failed = !succeeded)
            }
          finally
            trackedExecution.close()
            stdout.stream.close()
            stderr.stream.close()
        }

    def stateless[T](op: => T): T = lock.synchronized {
      try op
      finally
        resetToInitial()
        state = initialState.copy(quiet = true)
        modeContext = state.context.asInstanceOf[FreshContext]
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

  def interruptActiveExecution(): Boolean =
    globalSession.interruptActiveExecution()

  def runCode(
      builtins: Skill,
      skill: Skill,
      scalaCode: String,
      cancellationToken: CancellationToken = CancellationToken.Never
  ): Result[CodeOutput, String] = runCode(builtins, Seq(skill), scalaCode, cancellationToken)

  def runCode(
      builtins: Skill,
      selected: Seq[Skill],
      scalaCode: String,
      cancellationToken: CancellationToken
  ): Result[CodeOutput, String] =
    runCodeObserved(builtins, selected, scalaCode, cancellationToken, (_, _) => ())

  private[agent] def runCodeObserved(
      builtins: Skill,
      selected: Seq[Skill],
      scalaCode: String,
      cancellationToken: CancellationToken,
      onOutput: (ToolOutputChannel, String) => Unit
  ): Result[CodeOutput, String] = lock.synchronized {
    try
      prepareSkills(builtins, selected).flatMap { skills =>
        val available = selected ++ Skills.loadAll(os.pwd / "skills") :+ Skills.Basic
        Skills.resolve(selected.map(_.universe), available, builtins, includeApis = true).flatMap { apis =>
          globalSession.runExpression(builtins, skills, apis, scalaCode, cancellationToken, onOutput)
        }
      }
    catch
      case scala.util.control.NonFatal(error) => Result.Err(error.toString)
    finally ReplOutputStream.clearBuffer()
  }

  /** Prepare APIs/implementations without invoking capability factories or contacting a provider. */
  def prepareSkills(builtins: Skill, selected: Seq[Skill]): Result[Vector[Skill], String] =
    Result.catchException { case error => error.toString } {
      val available = selected ++ Skills.loadAll(os.pwd / "skills") :+ Skills.Basic
      Skills.resolve(selected.map(_.universe), available, builtins).map { skills =>
        compileLock.synchronized {
          ensureCompiled(builtins)
          val compileOrder = Skills.resolve(selected.map(_.universe), available, builtins, includeApis = true) match
            case Result.Ok(value) => value
            case Result.Err(error) => throw IllegalArgumentException(error)
          for skill <- compileOrder do
            val localApis = skill.apiDependencies.filter(name => available.exists(_.universe == name))
            val dependencyApis = Skills.resolve(skill.requires ++ localApis, available, builtins, includeApis = true) match
              case Result.Ok(value) => value
              case Result.Err(error) => throw IllegalArgumentException(error)
            val _ = compileSkill(skill, dependencyApis)
        }
        skills
      }
    }.flatMap(identity)

  /** Decode each channel independently; stderr output alone does not mean execution failed. */
  def decodeOutput(output: CodeOutput): ScalaToolResult =
    def decodeChannel(name: String, text: String): Vector[ujson.Value] =
      text.linesIterator.filter(_.trim.nonEmpty).map { line =>
        try
          val data = ujson.read(line)
          data.obj.get("special.success").orElse(data.obj.get("special.error")).getOrElse(data)
        catch
          case scala.util.control.NonFatal(error) =>
            throw IllegalArgumentException(s"Failed to parse structured $name output: $line. Error: ${error.getMessage}")
      }.toVector
    try
      val stdout = decodeChannel("stdout", output.stdout)
      val stderr = decodeChannel("stderr", output.stderr)
      if output.failed then
        ScalaToolResult.Failure(stderr.lastOption.getOrElse(ujson.Str("Execution failed")), stdout, stderr)
      else if stdout.isEmpty && stderr.isEmpty then ScalaToolResult.NoOutput
      else ScalaToolResult.Success(stdout, stderr)
    catch
      case scala.util.control.NonFatal(error) => ScalaToolResult.Failure(error.getMessage)

  def ensureCompiled(skill: Skill): Unit =
    if skill.requiresRuntimeClasspath then
      compileLock.synchronized {
        val _ = compileSkill(skill)
        ()
      }

  def runCodeHarness(
      builtins: Skill,
      skill: Skill,
      scalaCode: String,
      callId: Int,
      log: Logger,
      cancellationToken: CancellationToken = CancellationToken.Never
  )(using ExecutionContext): Future[ScalaToolResult] =
    runCodeHarness(builtins, Seq(skill), scalaCode, callId, log, cancellationToken)

  def runCodeHarness(
      builtins: Skill,
      skills: Seq[Skill],
      scalaCode: String,
      callId: Int,
      log: Logger,
      cancellationToken: CancellationToken
  )(using ExecutionContext): Future[ScalaToolResult] =
    def strippedAnsi(str: String): String =
      Option(str).getOrElse("Unknown error").replaceAll("\u001B\\[[;\\d]*m", "")
    val executionId = ToolExecutionHistory.nextId()
    log.executionStarted(executionId, skills.map(_.universe), scalaCode)
    def liveOutput(channel: ToolOutputChannel, line: String): Unit =
      val display = scala.util.Try {
        val data = ujson.read(line)
        val value = data.obj.get("special.success").orElse(data.obj.get("special.error")).getOrElse(data)
        value match
          case ujson.Str(text) => text
          case other => other.render(indent = 2)
      }.getOrElse(line.stripTrailing())
      log.executionOutput(executionId, channel, display + "\n")
    Future[Result[CodeOutput, String]] {
      scala.concurrent.blocking {
        if cancellationToken.isCancelled then Result.Err("cancelled")
        else
          val monitor =
            RuntimeStatus.monitor(
              log,
              s"running execution #$executionId",
              shouldUpdate = () => !cancellationToken.isCancelled
            )
          try
            log.executionRunning(executionId)
            runCodeObserved(builtins, skills, scalaCode, cancellationToken, liveOutput)
          finally monitor.close()
      }
    }.transform({ try0 =>
      val normalized = try0 match
        case scala.util.Success(res) =>
          res match
            case Result.Ok(value) =>
              decodeOutput(value)
            case Result.Err(error) =>
              ScalaToolResult.Failure(strippedAnsi(error))
        case scala.util.Failure(exception) =>
          ScalaToolResult.Failure(
            s"Exception while executing code: ${strippedAnsi(exception.getMessage)}"
          )
      val state =
        if cancellationToken.isCancelled then ToolExecutionState.Cancelled
        else normalized match
          case ScalaToolResult.Failure(error, _, _) =>
            log.executionOutput(executionId, ToolOutputChannel.Stderr,
              "Execution failed: " + error.render(indent = 2) + "\n")
            ToolExecutionState.Failed
          case _ => ToolExecutionState.Succeeded
      log.executionFinished(executionId, state)
      scala.util.Success(normalized)
    })

  def compileSkill(skill: Skill, dependencies: Seq[Skill] = Nil): os.Path = {
    val dependencyJars = dependencies.map(d => os.pwd / ".agent-runtime/interface" / d.universe / "sigs.jar")
    val dependencyFlags = dependencyJars.flatMap(path => Seq("--extra-jar", path.toString))
    def digestFrom(strings: String*): String = {
      val hasher = java.security.MessageDigest.getInstance("SHA-256")
      for sourceCode <- strings.filter(_.nonEmpty).map(_.getBytes()) do hasher.update(sourceCode)
      val digest = hasher.digest()
      val base64 = java.util.Base64.getEncoder.encodeToString(digest)
      base64
    }
    def withTempFile[T](prefix: os.Path, name: String)(f: os.Path => T): T = {
      val tempFile = prefix / name
      try f(tempFile)
      finally os.remove(tempFile)
    }
    def packageRuntime(arguments: Seq[String]): Unit =
      // Compiler subprocesses must not write over the full-screen terminal.
      val compiled = os.proc(arguments).call(check = false, stdout = os.Pipe, stderr = os.Pipe)
      if compiled.exitCode != 0 then
        throw IllegalStateException(compiled.out.text() + compiled.err.text())
    val outdir = os.pwd / ".agent-runtime" / "interface" / skill.name
    os.makeDir.all(outdir)
    val targetSigJar = outdir / "sigs.jar"
    val targetImplJar = outdir / "impl.jar"
    val existingSigs = scala.util.Try(os.read(outdir / "sigs.hash"))
    val digest =
      digestFrom("safe-api-publish-v1", ScalaVersion, skill.interface, skill.code, skill.api, skill.predef.getOrElse(""), skill.bindings.toString, skill.requires.toString,
        dependencyJars.map(path => java.util.Base64.getEncoder.encodeToString(os.read.bytes(path))).mkString("|"))
    val shouldWrite = !os.exists(targetSigJar) || !os
      .exists(targetImplJar) || existingSigs.map(_ != digest).getOrElse(true)
    if (shouldWrite) {
      // A failed rebuild must not leave a cache marker for partially replaced JARs.
      if os.exists(outdir / "sigs.hash") then os.remove(outdir / "sigs.hash")
      val tempDir = os.pwd / ".agent-runtime" / "temp" / skill.name
      try
        os.makeDir.all(tempDir)
        withTempFile(tempDir, "Interface.scala") { tempInterface =>
          val intfdirectives = skill.interface.linesIterator.toVector.takeWhile(line =>
            line.startsWith("//> using ") || line.trim.isEmpty()
          )
          os.write.over(tempInterface, skill.interface)
          withTempFile(tempDir, "sigs.jar") { sigsJar =>
            val _ = packageRuntime(
              Seq(
                "scala",
                "--power",
                "package",
                "-S",
                ScalaVersion,
                "--library",
                "-f",
                "-o",
                sigsJar.toString,
                "-language:experimental.safe",
                tempInterface.toString
              ) ++ dependencyFlags
            )
            os.copy(sigsJar, targetSigJar, replaceExisting = true)
            SkillArtifacts.publish(skill, tempInterface)
            withTempFile(tempDir, "Implementation.scala") { tempCode =>
              val (directives, file) = skill.code.linesIterator.toVector.span(line =>
                line.startsWith("//> using ") || line.trim.isEmpty()
              )
              val directives0 = (intfdirectives ++ directives)
                .filterNot(line =>
                  line.startsWith("//> using file ") || line.startsWith("//> using files ")
                )
              val code0 = (directives0 ++ file).mkString(java.lang.System.lineSeparator())
              os.write.over(tempCode, code0)
              withTempFile(tempDir, "impl.jar") { implJar =>
                val _ = packageRuntime(
                  Seq(
                    "scala",
                    "--power",
                    "package",
                    "-S",
                    ScalaVersion,
                    "--library",
                    "-f",
                    "-o",
                    implJar.toString,
                    "--extra-jar",
                    sigsJar.toString,
                    tempCode.toString
                  ) ++ dependencyFlags
                )
                os.copy(implJar, targetImplJar, replaceExisting = true)
              }
            }
          }
        }
        os.write.over(outdir / "sigs.hash", digest)
      finally os.remove.all(tempDir)
    }
    outdir
  }
}
