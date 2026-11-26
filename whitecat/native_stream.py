"""Project native app-server notifications into user-visible text and tool state."""
import threading
import time
from urllib.error import URLError,HTTPError
from shared_cli import SharedCLI,RpcError

TOOLS={"commandExecution":"shell","mcpToolCall":"mcp","dynamicToolCall":"tool",
       "webSearch":"web","fileChange":"files","imageView":"tool","imageGeneration":"tool","collabAgentToolCall":"tool"}

class Observer:
    def __init__(self,exe,post):
        self.exe=exe;self.post=post
        self.lock=threading.RLock()
        self.wanted=set();self.parts={};self.dirty={}
        self.ready=set()
        threading.Thread(target=self.run,daemon=True,name="native-stream").start()

    def watch(self,threads):
        with self.lock:self.wanted=set(threads)

    def replace(self,thread,turn,item,kind,body,status,progress=None,meta=None):
        key=(thread,turn,item);old=self.parts.get(key)
        if old and old["status"]!="running" and status=="running":return
        if progress is None:progress=old["progress"] if old else 0
        value=dict(turn=turn,item=item,kind=kind,body=body,status=status,progress=progress)
        for name,fallback in (("label",""),("started_ms",0),("ended_ms",0)):
            value[name]=(meta or {}).get(name) or (old or {}).get(name,fallback)
        if value!=old:self.parts[key]=self.dirty[key]=value

    def item(self,thread,turn,i,finished,notice=None):
        typ=i.get("type");key=i.get("id")
        if not key:return
        if typ=="agentMessage":
            kind="commentary" if i.get("phase")=="commentary" else "answer"
            body=i.get("text") or ""
        elif typ in TOOLS:kind=TOOLS[typ];body=""
        else:return
        status="done" if finished else "running"
        if i.get("status") in ("failed","declined") or (i.get("exitCode") not in (None,0)):status="failed"
        notice=notice or {}
        meta={"label":i.get("tool") if kind in ("mcp","tool") else "",
              "started_ms":i.get("startedAtMs") or notice.get("startedAtMs"),"ended_ms":i.get("completedAtMs") or notice.get("completedAtMs")}
        self.replace(thread,turn,key,kind,body,status,meta=meta)

    def notice(self,method,p):
        thread=p.get("threadId");turn=p.get("turnId");item=p.get("itemId")
        with self.lock:
            if thread not in self.wanted:return
            if method in ("item/started","item/completed") and turn:
                self.item(thread,turn,p.get("item") or {},method=="item/completed",p)
            elif turn and item and method=="item/agentMessage/delta":
                delta=p.get("delta")
                if not isinstance(delta,str) or not delta:return
                old=self.parts.get((thread,turn,item),{})
                self.replace(thread,turn,item,old.get("kind","answer"),old.get("body","")+delta,"running")
            elif turn and item and method in ("item/commandExecution/outputDelta","item/mcpToolCall/progress","item/fileChange/outputDelta"):
                old=self.parts.get((thread,turn,item))
                if old:self.replace(thread,turn,item,old["kind"],"","running",old["progress"]+1)
            elif method=="turn/completed":
                data=p.get("turn") or {};self.seed(thread,[data],False)

    def seed(self,thread,turns,recover):
        with self.lock:
            for turn in turns:
                tid=turn.get("id")
                if not tid:continue
                ended=turn.get("status") in ("completed","interrupted","failed")
                for i in turn.get("items") or []:
                    done=ended or i.get("status") in ("completed","failed","declined") or i.get("completedAtMs") is not None
                    self.item(thread,tid,i,done)
                if ended:
                    for (t,u,key),old in list(self.parts.items()):
                        if (t,u)==(thread,tid) and old["status"]=="running":
                            self.replace(t,u,key,old["kind"],old["body"],"interrupted" if turn.get("status")!="completed" else "done")
            if recover:self.ready.add(thread)

    def flush(self):
        with self.lock:batch=list(self.dirty.items())
        groups={}
        for key,value in batch:groups.setdefault(key[0],[]).append((key,value))
        for thread,items in groups.items():
            for i in range(0,len(items),12):
                chunk=items[i:i+12]
                # Limit an HTTP batch even for a very long answer.
                self.publish(thread,chunk)

    def publish(self,thread,chunk):
        if len(chunk)>1 and sum(len(v["body"].encode()) for _,v in chunk)>500000:
            mid=len(chunk)//2;self.publish(thread,chunk[:mid]);self.publish(thread,chunk[mid:]);return
        try:self.post("stream",{"thread":thread,"parts":[v for _,v in chunk]})
        except (OSError,URLError):return
        with self.lock:
            for k,v in chunk:
                if self.dirty.get(k) is v:self.dirty.pop(k)
            if len(self.parts)>1536:
                for k,v in list(self.parts.items()):
                    if len(self.parts)<=1024:break
                    if k not in self.dirty and v["status"]!="running":self.parts.pop(k)

    def run(self):
        client=None;attached=set();next_check=0
        while True:
            try:
                with self.lock:wanted=self.wanted.copy()
                if time.monotonic()>=next_check and (wanted or attached):
                    if not client:client=SharedCLI(self.exe,self.notice)
                    loaded=client.loaded()
                    for tid in attached-wanted:
                        try:client.call("thread/unsubscribe",{"threadId":tid})
                        except RpcError:pass
                        attached.discard(tid)
                    for tid in sorted((wanted & loaded)-attached):
                        client.call("thread/resume",{"threadId":tid,"excludeTurns":True})
                        client.call("thread/turns/list",{"threadId":tid,"limit":4,"sortDirection":"desc","itemsView":"full"},
                                    lambda result,t=tid:self.seed(t,reversed(result["data"]),True))
                        attached.add(tid)
                    next_check=time.monotonic()+1
                self.flush()
                time.sleep(.10)
            except (ImportError,OSError,RpcError,ValueError):
                if client:client.close()
                client=None;attached.clear()
                with self.lock:self.ready.clear()
                time.sleep(1)
