"""SDK-shaped fixture: blocking streaming iterator and callbacks on a different loop."""
import asyncio
import json
import queue
import threading


def generable(_):
    def decorate(cls):
        cls.generation_schema = staticmethod(lambda: {})
        return cls
    return decorate


def guide(*_, **__):return None


class Tool:pass


class ExceededContextWindowSizeError(Exception):pass


class GenerationOptions:
    def __init__(self,maximum_response_tokens=None):self.maximum_response_tokens=maximum_response_tokens


class SystemLanguageModel:
    context_size=4096
    async def token_count(self,value):
        if isinstance(value,Transcript):value=json.dumps(value.state)
        return len(value)//4+1



class GeneratedContent:
    def value(self):return {'universe':'basic','scala_code':'puts(13 * 17)','universes':[]}


class Transcript:
    def __init__(self,state=None):self.state=state or {'fixture':'native-state','turn':0}
    async def to_dict(self):return self.state
    @classmethod
    async def from_dict(cls,state):return cls(state)


class LanguageModelSession:
    def __init__(self,instructions=None,tools=None,model=None):
        self.tools=tools
        self.transcript=Transcript()
    @classmethod
    def from_transcript(cls,transcript,tools=None,model=None):
        session=cls(tools=tools)
        session.transcript=transcript
        return session
    async def stream_response(self,prompt,options=None):
        if self.transcript.state.get('fail_before') and any(e.get('role')=='user' for e in self.transcript.state.get('transcript',{}).get('entries',[])):
            raise ExceededContextWindowSizeError('Context window size exceeded: None')
        completed=queue.Queue()
        def callback():
            completed.put(asyncio.run(self.tools[0].call(GeneratedContent())))
        threading.Thread(target=callback,daemon=True).start()
        # Reproduce SDK 0.2.1's blocking async iterator: the result reader must
        # run independently, and callback futures must work across event loops.
        result=completed.get(timeout=4)
        parsed=json.loads(result)
        assert parsed.get('output_truncated') or parsed['stdout']==[221]
        self.transcript.state['observed_result']=parsed
        if self.transcript.state.get('fail_after'):
            raise ExceededContextWindowSizeError('Context window size exceeded: None')
        self.transcript.state['turn']=self.transcript.state.get('turn',0)+1
        yield '2'
        yield '221'
