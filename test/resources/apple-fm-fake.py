"""Platform-independent stand-in for the optional native process transport."""
import json,os,sys,time
request=json.loads(sys.stdin.readline())
def emit(event):print(json.dumps(event),flush=True)
mode=sys.argv[1]
if mode=='wait':
    emit({'type':'content','text':str(os.getpid())})
    while True:time.sleep(1)
elif mode=='crash':
    print('FAKE SDK LOAD FAILED',file=sys.stderr,flush=True)
    sys.exit(7)
else:
    assert request['prompt']=='calculate'
    emit({'type':'tool_call','id':'call-1','name':'run_scala_code','arguments':{'universe':'basic','scala_code':'puts(13 * 17)'}})
    result=json.loads(sys.stdin.readline())
    assert result=={'type':'tool_result','id':'call-1','result':'{"success":true,"stdout":[221],"stderr":[]}'}
    emit({'type':'content','text':'221'})
    emit({'type':'state','transcript':{'version':'test','entries':['completed']}})
    emit({'type':'done'})
