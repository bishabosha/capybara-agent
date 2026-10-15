"""Optional Apple SDK bridge. JSON-lines on stdio; generated Scala executes only in the JVM."""
import asyncio
import copy
from concurrent.futures import Future
import json
import platform
import sys
import threading
import uuid

_output_lock = threading.Lock()


def emit(event):
    with _output_lock:
        print(json.dumps(event, ensure_ascii=False), flush=True)


def trim_turns(state, keep=None):
    """Remove whole user turns, never separating native tool calls from results."""
    entries = state.get("transcript", {}).get("entries", [])
    starts = [index for index, entry in enumerate(entries) if entry.get("role") == "user"]
    if not starts:
        return None, 0
    removed = 1 if keep is None else max(0, len(starts) - keep)
    if removed == 0:
        return None, 0
    remaining = entries[starts[removed]:] if removed < len(starts) else []
    trimmed = copy.deepcopy(state)
    trimmed["transcript"]["entries"] = copy.deepcopy(entries[:starts[0]] + remaining)
    return trimmed, removed


async def bound_result(model, result):
    """Only shorten the SDK's view; the JVM retains the original tool result."""
    budget = max(128, min(512, model.context_size // 8))
    if await model.token_count(result) <= budget:
        return result
    try:
        value = json.loads(result)
    except ValueError:
        value = {"stdout": [result]}
    def text(channel):
        return "\n".join(item if isinstance(item, str) else json.dumps(item, ensure_ascii=False)
                         for item in value.get(channel, []))
    stdout, stderr = text("stdout"), text("stderr")
    error = str(value.get("executionError", ""))
    def preview(length):
        packet = {"success": not bool(error), "stdout": [stdout[:length]] if stdout else [],
                  "stderr": [stderr[:length]] if stderr else [], "output_truncated": True,
                  "note": "Output shortened for model context; original result retained by the harness."}
        if error:
            packet["executionError"] = error[:length]
        return json.dumps(packet, ensure_ascii=False)
    low, high = 0, max(len(stdout), len(stderr), len(error))
    while low < high:
        middle = (low + high + 1) // 2
        if await model.token_count(preview(middle)) <= budget:
            low = middle
        else:
            high = middle - 1
    emit({"type": "notice", "text": "Apple FM received a shortened tool result to fit its context; the harness retained the original."})
    return preview(low)


def run(request):
    # Keep native imports behind explicit provider selection and the platform check.
    if platform.system() != "Darwin":
        raise RuntimeError("apple-fm requires macOS and Apple Intelligence; select another provider on this platform")
    try:
        import apple_fm_sdk as fm
    except ImportError as error:
        raise RuntimeError("Apple SDK missing. Install apple-fm-sdk in a virtual environment and set CAPYBARA_APPLE_FM_PYTHON to its Python executable") from error

    @fm.generable("Arguments for the JVM's Scala execution tool")
    class Arguments:
        universe: str = fm.guide("Use basic for arithmetic, strings and collections; otherwise select a documented skill.")
        scala_code: str = fm.guide("Valid Scala. Call puts(value) explicitly to print output; the final expression is discarded.")
        universes: list[str] = fm.guide("Empty for one universe. For composition, list the documented skill names.")

    pending = {}
    lock = threading.Lock()
    calls = 0
    generation = 0
    model = None

    class ScalaTool(fm.Tool):
        name = "run_scala_code"
        description = request["tool_description"]

        def __init__(self, epoch):
            self.epoch = epoch
            super().__init__()

        @property
        def arguments_schema(self):
            return Arguments.generation_schema()

        async def call(self, arguments):
            nonlocal calls
            call_id = str(uuid.uuid4())
            future = Future()
            with lock:
                if self.epoch != generation:
                    raise RuntimeError("Discarded callback from an earlier context attempt")
                calls += 1
                if calls > 32:
                    emit({"type": "error", "message": "Apple FM exceeded 32 tool calls in one turn"})
                    raise RuntimeError("Tool call limit exceeded")
                pending[call_id] = future
            emit({"type": "tool_call", "id": call_id, "name": self.name, "arguments": arguments.value()})
            # SDK callbacks can run on their own threads/event loops.
            result = await asyncio.wrap_future(future)
            return await bound_result(model, result)

    async def generate():
        nonlocal model, generation
        model = fm.SystemLanguageModel()
        capacity = model.context_size
        prompt_tokens = await model.token_count(request["prompt"])
        reserve = min(768, capacity // 5)

        async def restore(state, tool):
            if state is not None:
                transcript = await fm.Transcript.from_dict(state)
                return fm.LanguageModelSession.from_transcript(transcript, model=model, tools=[tool])
            return fm.LanguageModelSession(instructions=request["instructions"], model=model, tools=[tool])

        state = request.get("transcript")
        tool = ScalaTool(generation)
        session = await restore(state, tool)
        removed = 0
        while True:
            used = await model.token_count(session.transcript) + prompt_tokens + 64
            if used + reserve <= capacity:
                break
            current = await session.transcript.to_dict()
            turns = sum(entry.get("role") == "user" for entry in current.get("transcript", {}).get("entries", []))
            # Prefer preserving the most recent turn over a generous response budget.
            if turns <= 1 and used + 256 <= capacity:
                reserve = min(reserve, capacity - used)
                break
            state, count = trim_turns(current)
            if state is None:
                raise RuntimeError("Apple FM cannot fit the current prompt and tool instructions. Shorten this request or reduce the enabled skills.")
            removed += count
            session = await restore(state, tool)
        if removed:
            emit({"type": "notice", "text": f"Apple FM shortened its model context by {removed} older turn(s); terminal history is unchanged."})

        prepared = await session.transcript.to_dict()
        for attempt in range(2):
            previous = ""
            try:
                options = fm.GenerationOptions(maximum_response_tokens=reserve)
                async for snapshot in session.stream_response(request["prompt"], options=options):
                    # The SDK yields cumulative text, rather than deltas.
                    if not snapshot.startswith(previous):
                        raise ValueError("Apple SDK revised previously streamed text")
                    delta = snapshot[len(previous):]
                    if delta:
                        emit({"type": "content", "text": delta})
                    previous = snapshot
                emit({"type": "state", "transcript": await session.transcript.to_dict()})
                emit({"type": "done"})
                return
            except fm.ExceededContextWindowSizeError as error:
                # A retry is allowed only before any execution or visible answer.
                # Invalidate the old tool instance atomically, including late callbacks.
                with lock:
                    can_retry = attempt == 0 and calls == 0 and not previous
                    if can_retry:
                        generation += 1
                trimmed, count = trim_turns(prepared, keep=0) if can_retry else (None, 0)
                if trimmed is None:
                    raise RuntimeError("Apple FM ran out of context during this request. Already executed tools were not repeated; their output remains in execution history. Use a smaller task or shorter output.") from error
                emit({"type": "notice", "text": f"Apple FM renewed its context, removing {count} older turn(s); no tools were replayed."})
                session = await restore(trimmed, ScalaTool(generation))

    def generation_thread():
        try:
            asyncio.run(generate())
        except Exception as error:
            emit({"type": "error", "message": str(error)})

    # SDK 0.2.1's async streaming iterator polls a blocking queue. Keep it off
    # the stdin reader's thread, so native tool callbacks can receive JVM results.
    worker = threading.Thread(target=generation_thread, daemon=True)
    worker.start()
    for line in sys.stdin:
        event = json.loads(line)
        if event.get("type") != "tool_result":
            raise ValueError("Unexpected bridge input")
        with lock:
            future = pending.pop(event.get("id"), None)
        if future is None:
            raise ValueError("Unexpected tool result call ID")
        if not isinstance(event.get("result"), str):
            raise ValueError("Tool result must be a JSON string")
        future.set_result(event["result"])
    # The JVM closes stdin after done, or terminates the subprocess on cancellation.


def main():
    try:
        request = json.loads(sys.stdin.readline())
        run(request)
    except Exception as error:
        emit({"type": "error", "message": str(error)})
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
