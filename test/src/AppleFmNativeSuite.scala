package capybara.agent

/** Optional acceptance checks; enable explicitly on a supported Mac with the native SDK. */
class AppleFmNativeSuite extends munit.FunSuite:
  private def nativePython: String =
    assume(sys.props.getOrElse("os.name", "").startsWith("Mac"), "Native Apple acceptance requires macOS")
    val configured = sys.env.get("CAPYBARA_TEST_APPLE_FM_PYTHON").filter(_.nonEmpty)
    assume(configured.nonEmpty, "Set CAPYBARA_TEST_APPLE_FM_PYTHON to opt into native SDK acceptance checks")
    configured.get

  override val munitTimeout = scala.concurrent.duration.Duration(70, "s")
  test("native SDK provider executes Scala and completes the host agent turn") {
    val python = nativePython
    val java = os.Path(sys.props("java.home")) / "bin/java"
    val process = os.proc(java.toString, "-cp", sys.props("java.class.path"), "capybara.agent.run",
      "--query", "Call run_scala_code with universe basic, universes empty, and scala_code exactly puts(13 * 17). Then answer using the tool result.")
      .call(check = false, stderr = os.Pipe, timeout = 60000, env = Map(
        "CAPYBARA_PROVIDER" -> "apple-fm",
        "CAPYBARA_MODEL" -> "system",
        "CAPYBARA_APPLE_FM_PYTHON" -> python))
    val output = process.out.text() + process.err.text()
    assertEquals(process.exitCode, 0, output)
    assert(output.contains("succeeded"), output)
    assert(output.contains("221"), output)
  }

  test("native transcript resumes across two interactive turns") {
    val python = nativePython
    val javaPath = os.Path(sys.props("java.home")) / "bin/java"
    val builder = new ProcessBuilder(javaPath.toString, "-cp", sys.props("java.class.path"), "capybara.agent.run")
    builder.redirectErrorStream(true)
    val _ = builder.environment().put("CAPYBARA_PROVIDER", "apple-fm")
    val _ = builder.environment().put("CAPYBARA_MODEL", "system")
    val _ = builder.environment().put("CAPYBARA_APPLE_FM_PYTHON", python)
    val _ = builder.environment().put("TERM", "dumb")
    val process = builder.start()
    val timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    val _ = timer.schedule(new Runnable { def run(): Unit = { process.destroyForcibly(); () } },
      60, java.util.concurrent.TimeUnit.SECONDS)
    val writer = new java.io.BufferedWriter(new java.io.OutputStreamWriter(process.getOutputStream))
    val reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream))
    val output = new StringBuilder
    def send(text: String): Unit = { writer.write(text + "\n"); writer.flush() }
    def ready(): Unit =
      var line = reader.readLine()
      while line != null && !line.contains("[your turn]") do
        output.append(line + "\n")
        line = reader.readLine()
      assert(line != null, output.toString)
    try
      send("Call run_scala_code with universe basic and scala_code exactly puts(13 * 17). Answer using stdout.")
      ready()
      assert(output.toString.contains("221"), output.toString)
      send("Add 9 to the previous result using run_scala_code and puts. Then answer using stdout.")
      ready()
      assert(output.toString.contains("230"), output.toString)
      assert(!output.toString.contains("[error]"), output.toString)
      send(":q")
      assert(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS), output.toString)
    finally
      writer.close()
      process.destroyForcibly()
      val _ = timer.shutdownNow()
  }
