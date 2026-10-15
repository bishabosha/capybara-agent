"""Run the real harness in a PTY against a loopback-only streaming provider."""
import fcntl
import http.server
import json
import os
import pty
import select
import signal
import struct
import sys
import termios
import tempfile
from pathlib import Path
import threading
import time
from terminal_screen import Screen

requests = 0


class Provider(http.server.BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_POST(self):
        global requests
        self.rfile.read(int(self.headers.get("Content-Length", "0")))
        requests += 1
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()

        def event(delta, finish=None):
            data = {"choices": [{"index": 0, "delta": delta, "finish_reason": finish}]}
            self.wfile.write(("data: " + json.dumps(data) + "\n\n").encode())
            self.wfile.flush()
            time.sleep(0.25)

        if requests == 1:
            event({"reasoning_content": "FIRST-LIVE-THOUGHT\n"})
            event({"reasoning_content": "SECOND-STREAMED-THOUGHT\n"})
            event({"tool_calls": [{"index": 0, "id": "replay-call", "type": "function", "function": {
                "name": "run_scala_code", "arguments": json.dumps({"universe": "clock", "scala_code":
                    'puts("LIVE-STDOUT-START"); waiter.pause(1600); putsErr("LIVE-STDERR-MIDDLE"); waiter.pause(1600); (1 to 30).foreach(n => puts("OUT-LINE-" + n)); puts("LIVE-STDOUT-END")' +
                    ''.join('\n// CODE-LINE-%02d' % n for n in range(30))})
            }}]}, "tool_calls")
        elif requests == 2:
            event({"tool_calls": [{"index": 0, "id": "second-call", "type": "function", "function": {
                "name": "run_scala_code", "arguments": json.dumps({"universe": "basic", "scala_code": "puts(42)"})
            }}]}, "tool_calls")
        else:
            history = "".join("FRAME-HISTORY-%03d\n" % n for n in range(100))
            event({"content": history + "REPLAY-FINISHED\n"}, "stop")
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()


server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Provider)
env = dict(os.environ, TERM="xterm-256color", CAPYBARA_PROVIDER="openai", CAPYBARA_MODEL="local-replay",
           CAPYBARA_BASE_URL="http://127.0.0.1:%d/v1" % server.server_port)
# Other providers must not probe Python or native Apple dependencies.
env["CAPYBARA_APPLE_FM_PYTHON"] = "/definitely-missing/apple-fm-python"
env.pop("CAPYBARA_API_KEY", None)
env.pop("OPENAI_API_KEY", None)
project = Path.cwd()
workspace = tempfile.TemporaryDirectory(prefix="capybara-replay-")
root = Path(workspace.name)
(root / "builtin-skills").symlink_to(project / "builtin-skills", target_is_directory=True)
scripts = root / "skills/clock/scripts"
scripts.mkdir(parents=True)
(scripts.parent / "SKILL.md").write_text("---\nname: clock\ndescription: Test-only waiting capability.\n---\n")
(scripts / "Interface.scala").write_text("package replayclock\ntrait Wait extends caps.SharedCapability { def pause(ms: Long): Unit }\n")
(scripts / "Implementation.scala").write_text('''//> using file Interface.scala
package replayclock.internal
import scala.language.experimental.{captureChecking, separationChecking}
private class Waiting extends replayclock.Wait {
  def pause(ms: Long): Unit = Thread.sleep(ms)
}
@caps.rejectSafe
def impl(): replayclock.Wait = new Waiting
''')
(scripts / "Manifest.scala").write_text('''val Manifest = (
  universe = "clock", api = "waiter.pause(ms) waits for the given milliseconds.", predef = "",
  bindings = Vector((name = "waiter", tpe = "replayclock.Wait", value = "replayclock.internal.impl()"))
)
''')
pid, master = pty.fork()
if pid == 0:
    os.chdir(root)
    os.execve(sys.argv[1], [sys.argv[1], "-cp", sys.argv[2], "capybara.agent.run",
                           "--plugin-dir", str(project / "examples/plugins")], env)
fcntl.ioctl(master, termios.TIOCSWINSZ, struct.pack("HHHH", 32, 110, 0, 0))
threading.Thread(target=server.serve_forever, daemon=True).start()
captured = bytearray()
screen = Screen(32, 110)


def until(marker, timeout=30):
    deadline = time.monotonic() + timeout
    while marker not in captured and time.monotonic() < deadline:
        if select.select([master], [], [], 0.1)[0]:
            try:
                data = os.read(master, 65536)
            except OSError:
                break
            if not data:
                break
            captured.extend(data)
            screen.feed(data)
            # Answer JLine's device cursor query like a terminal emulator would.
            if b"\x1b[6n" in data:
                os.write(master, b"\x1b[1;1R")
    assert marker in captured, "Missing %r; terminal tail: %r" % (marker, bytes(captured[-1200:]))


def until_screen(condition, timeout=5):
    deadline = time.monotonic() + timeout
    while not condition() and time.monotonic() < deadline:
        if select.select([master], [], [], 0.1)[0]:
            try:
                data = os.read(master, 65536)
            except OSError:
                break
            captured.extend(data)
            screen.feed(data)
            if b"\x1b[6n" in data:
                os.write(master, b"\x1b[1;1R")
    assert condition(), repr(screen.text())


def output_pane():
    return "\n".join(line.rsplit(" | ", 1)[-1] for line in screen.text()[:6])


try:
    print("Starting replay terminal", flush=True)
    until(b"Capybara")
    print("Replay terminal ready", flush=True)
    # Exercise the actual JLine completion engine under the full-screen renderer.
    os.write(master, b"/the\t plain\n")
    until(b"Plain theme enabled.", timeout=10)
    os.write(master, b"/theme aurora\n")
    until(b"Aurora theme enabled.", timeout=10)
    print("Using default response-only transcript", flush=True)
    captured.clear()
    os.write(master, b"calculate the result with the basic skill\n")
    until_screen(lambda: "[stdout] LIVE-STDOUT-START" in output_pane(), timeout=30)
    assert requests == 1, "stdout should render while the first tool is still running"
    assert "running" in screen.text()[0], repr(screen.text())
    assert any("run_scala_code" in line and "tool #" in line for line in screen.text()[7:-3]), repr(screen.text())
    # Four bottom rows are the fixed divider, status, and two-line composer.
    conversation = screen.text()[7:-4]
    assert not any(line.strip() and all(c in "─q" for c in line.strip()) for line in conversation), repr(screen.text())
    assert not any("\x7f" in line for line in conversation), repr(screen.text())
    prompt_row = next(i for i, line in enumerate(conversation) if line.startswith("> calculate"))
    assert "tool #" in conversation[prompt_row + 1], repr(screen.text())
    assert not any("[stdout]" in line or line.lstrip().startswith("execution #") or
                   (("LIVE-STDOUT-START" in line or "waiter.pause" in line) and "tool #" not in line)
                   for line in screen.text()[7:-3]), repr(screen.text())
    until_screen(lambda: "[stderr] LIVE-STDERR-MIDDLE" in output_pane(), timeout=8)
    assert requests == 1, "stderr should render before the tool completes"
    assert not any("LIVE-STDERR-MIDDLE" in line and "tool #" not in line
                   for line in screen.text()[7:-3]), repr(screen.text())
    until(b"REPLAY-FINISHED", timeout=20)
    print("Provider requests completed", flush=True)
    # These roles are hidden from normal transcript writes. Seeing each marker
    # proves that the provider -> agent -> panel -> actual PTY repaint path ran.
    for marker in [b"FIRST-LIVE-THOUGHT", b"SECOND-STREAMED"]:
        assert marker in captured, "Panel stream did not render %r" % marker
    # Wait for the final editor redraw, then check screen coordinates, not just
    # the presence of bytes in scrollback.
    captured.clear()
    os.write(master, b"draft-at-bottom")
    until_screen(lambda: "draft-at-bottom" in screen.text()[-1] and screen.row == 31)
    lines = screen.text()
    print("Checking terminal layout", flush=True)
    assert "Thinking" in lines[0] and "Executions" in lines[0], repr(lines)
    assert any("REPLAY-FINISHED" in line for line in lines[7:-3]), repr(lines)
    assert "draft-at-bottom" in lines[-1], repr(lines)
    assert screen.row == 31, (screen.row, lines)
    # Resize the actual PTY and check that the cursor/composer move to its new
    # final row while the live panels remain at row zero.
    screen.resize(24, 80)
    fcntl.ioctl(master, termios.TIOCSWINSZ, struct.pack("HHHH", 24, 80, 0, 0))
    os.kill(pid, signal.SIGWINCH)
    captured.clear()
    os.write(master, b"-resized")
    until_screen(lambda: "Executions" in screen.text()[0] and screen.row == 23 and
                 "draft-at-bottom-resized" in screen.text()[-1])
    lines = screen.text()
    assert "Thinking" in lines[0] and "Executions" in lines[0], repr(lines)
    assert "draft-at-bottom-resized" in lines[-1], repr(lines)
    assert screen.row == 23, (screen.row, lines)

    print("Resize passed", flush=True)
    # Laptop-friendly ASCII chords scroll actual conversation history, rather
    # than replacing the draft with a previously submitted command.
    until_screen(lambda: any("ready" in line for line in screen.text()))
    os.write(master, b"\x18p")
    until_screen(lambda: any("FRAME-HISTORY-080" in line for line in screen.text()))
    assert "draft-at-bottom-resized" in screen.text()[-1], repr(screen.text())
    assert not any("REPLAY-FINISHED" in line for line in screen.text()[7:-3]), repr(screen.text())
    os.write(master, b"\x18n")
    until_screen(lambda: any("REPLAY-FINISHED" in line for line in screen.text()[7:-3]))
    assert "draft-at-bottom-resized" in screen.text()[-1], repr(screen.text())
    # Also check the ordinary Page Up/Down sequences and retained plain-arrow
    # command history before hiding the panels and clearing the conversation.
    os.write(master, b"\x1b[5~")
    until_screen(lambda: any("FRAME-HISTORY-080" in line for line in screen.text()))
    os.write(master, b"\x1b[6~")
    until_screen(lambda: any("REPLAY-FINISHED" in line for line in screen.text()[7:-3]))
    os.write(master, b"\x15\x1bOA")
    until_screen(lambda: "calculate the result" in screen.text()[-1])
    print("Transcript scrolling passed", flush=True)
    # Inspect the previous call: both panes must switch together, then follow latest.
    os.write(master, b"\x15/execution older\n")
    until_screen(lambda: "LIVE-STDOUT-END" in output_pane())
    code = "".join(line.split(" | ")[1] for line in screen.text()[1:6])
    assert "waiter.pause" in code, repr(screen.text())
    # Laptop-friendly focus chords route scrolling to each panel without editing the draft.
    os.write(master, b"panel-draft\x18f")
    until_screen(lambda: "> Executions" in "\n".join(screen.text()[:6]))
    os.write(master, b"\x18n")
    until_screen(lambda: "CODE-LINE-" in "\n".join(screen.text()[:6]))
    assert "panel-draft" in screen.text()[-1], repr(screen.text())
    os.write(master, b"\x18f\x18t")
    until_screen(lambda: "> Output" in "\n".join(screen.text()[:6]) and "LIVE-STDOUT-START" in output_pane())
    assert "CODE-LINE-" in "\n".join(screen.text()[:6]), "Code scroll position must survive output navigation"
    os.write(master, b"\x18n")
    until_screen(lambda: "LIVE-STDOUT-START" not in output_pane() and "OUT-LINE-" in output_pane())
    os.write(master, b"\x18b")
    until_screen(lambda: "LIVE-STDOUT-END" in output_pane())
    os.write(master, b"\x18f")
    until_screen(lambda: "Focus: Conversation" in "\n".join(screen.text()))
    assert "panel-draft" in screen.text()[-1], repr(screen.text())
    os.write(master, b"\x18e\x18t")
    until_screen(lambda: "> Executions" in "\n".join(screen.text()[:6]) and
                 "waiter.pause" in "".join(line.split(" | ")[1] for line in screen.text()[1:6]))
    os.write(master, b"\x18o")
    until_screen(lambda: "> Output" in "\n".join(screen.text()[:6]))
    os.write(master, b"\x18c\x15")
    os.write(master, b"/execution latest\n")
    until_screen(lambda: "puts(42)" in "\n".join(screen.text()[:6]) and
                 "[stdout] 42" in output_pane() and "LIVE-STDOUT-END" not in output_pane())
    os.write(master, b"/split\n")
    captured.clear()
    until(b"Split view off.", timeout=5)
    assert not any("Thinking" in line for line in screen.text()[:6]), repr(screen.text())
    os.write(master, b"/clear\n")
    captured.clear()
    until(b"conversation cleared", timeout=5)
    assert not any("REPLAY-FINISHED" in line for line in screen.text()), repr(screen.text())
    os.write(master, b"final-draft")
    captured.clear()
    until_screen(lambda: "final-draft" in screen.text()[-1])
    assert "final-draft" in screen.text()[-1], repr(screen.text())
    assert requests == 3, "Expected tool execution and a follow-up model response"
    os.write(master, b"\x15:q\n")
    print("Real PTY replay passed: streamed thinking, tool panel, tool execution, final response.")
finally:
    print("Cleaning up replay", flush=True)
    server.shutdown()
    print("Server stopped", flush=True)
    try:
        os.kill(pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    # macOS can leave a killed PTY child exiting until its master is closed.
    os.close(master)
    os.waitpid(pid, 0)
    workspace.cleanup()
    print("Workspace cleaned", flush=True)
