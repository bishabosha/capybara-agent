package capybara.agent

class TerminalReplaySuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(100, "s")

  test("real terminal keeps panels above conversation and editor at bottom while streaming, editing and resizing") {
    val java = os.Path(sys.props("java.home")) / "bin/java"
    val replay = os.proc("python3", (os.pwd / "test/resources/terminal-stream-replay.py").toString,
      java.toString, sys.props("java.class.path")).call(check = false, stderr = os.Pipe, timeout = 90000)
    assertEquals(replay.exitCode, 0, replay.out.text() + replay.err.text())
  }
