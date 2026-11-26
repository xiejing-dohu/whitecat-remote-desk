"""JSON-RPC to the current user's official Codex app-server via its local control socket."""
import json
import queue
import subprocess
import threading


class RpcError(Exception):
    def __init__(self, error):
        self.code=error.get("code")
        super().__init__(error.get("message","Codex RPC failed"))


class SharedCLI:
    def __init__(self,exe,on_notice=None):
        self.on_notice=on_notice
        self.result_hooks={}
        from wsproto import WSConnection,ConnectionType
        from wsproto.events import Request
        self.ws=WSConnection(ConnectionType.CLIENT)
        self.proc=subprocess.Popen([exe,"app-server","proxy"],stdin=subprocess.PIPE,stdout=subprocess.PIPE,
                                   stderr=subprocess.DEVNULL,creationflags=getattr(subprocess,"CREATE_NO_WINDOW",0))
        self.messages=queue.Queue()
        self.serial=0
        self.lock=threading.Lock()
        try:
            self.send(Request(host="localhost",target="/"))
            self.reader=threading.Thread(target=self.read_loop,daemon=True)
            self.reader.start()
            if self.take()!="connected":raise ConnectionError("Codex handshake failed")
            self.call("initialize",{"clientInfo":{"name":"whitecat_station","version":"0.4"},
                                    "capabilities":{"experimentalApi":True}})
            self.packet({"method":"initialized"})
        except BaseException:
            self.close()
            raise

    def close(self):
        if self.proc.poll() is None:self.proc.kill()
        self.proc.wait()
        self.proc.stdin.close()
        self.proc.stdout.close()

    def send(self,event):
        with self.lock:
            self.proc.stdin.write(self.ws.send(event));self.proc.stdin.flush()

    def packet(self,obj):
        from wsproto.events import TextMessage
        self.send(TextMessage(data=json.dumps(obj,ensure_ascii=False,separators=(",",":"))))

    def read_loop(self):
        from wsproto.events import AcceptConnection,CloseConnection,Ping,TextMessage
        chunks=[]
        try:
            while block:=self.proc.stdout.read1(65536):
                with self.lock:
                    self.ws.receive_data(block)
                    events=list(self.ws.events())
                for ev in events:
                    if isinstance(ev,AcceptConnection):self.messages.put("connected")
                    elif isinstance(ev,Ping):self.send(ev.response())
                    elif isinstance(ev,CloseConnection):return
                    elif isinstance(ev,TextMessage):
                        chunks.append(ev.data)
                        if ev.message_finished:
                            msg=json.loads("".join(chunks));chunks=[]
                            if "id" in msg and "method" not in msg:
                                hook=self.result_hooks.pop(msg["id"],None)
                                if hook and "result" in msg:hook(msg["result"])
                                self.messages.put(msg)
                            elif self.on_notice and "method" in msg and "id" not in msg:
                                self.on_notice(msg["method"],msg.get("params") or {})
        except (ValueError,OSError):pass
        finally:self.messages.put(None)

    def take(self):
        try:msg=self.messages.get(timeout=8)
        except queue.Empty:raise TimeoutError("Codex control socket timed out") from None
        if msg is None:raise ConnectionError("Codex control socket closed")
        return msg

    def call(self,method,params,on_result=None):
        self.serial+=1
        if on_result:self.result_hooks[self.serial]=on_result
        self.packet({"id":self.serial,"method":method,"params":params})
        while True:
            result=self.take()
            if result.get("id")==self.serial:
                if "error" in result:raise RpcError(result["error"])
                return result["result"]

    def loaded(self):
        ids=set();cursor=None
        while True:
            page=self.call("thread/loaded/list",{"cursor":cursor,"limit":100})
            ids.update(page["data"]);cursor=page.get("nextCursor")
            if not cursor:return ids


class Control:
    """Reconnect without restarting Codex or displacing any TUI's writer."""
    def __init__(self,exe):
        self.exe=exe
        self.client=None
        self.retry_at=0

    def reset(self):
        import time
        if self.client:self.client.close()
        self.client=None
        self.retry_at=time.monotonic()+4

    def loaded(self):
        import time
        if not self.client and time.monotonic()<self.retry_at:return set()
        try:
            if not self.client:self.client=SharedCLI(self.exe)
            return self.client.loaded()
        except (ImportError,OSError,RpcError):
            self.reset()
            return set()

    def enqueue(self,thread,job):
        text=job["prompt"]
        if job["filename"]:
            text+="\n\n[文本附件 "+job["filename"]+"]\n"+job["sample"]
        response=self.client.call("thread/queue/add",{"threadId":thread,"clientUserMessageId":job["id"],
                                  "input":[{"type":"text","text":text,"text_elements":[]}]})
        if response["queuedSubmission"]["clientUserMessageId"]!=job["id"]:
            raise ConnectionError("Codex returned a different message identifier")

    def find(self,thread,job):
        queued=self.client.call("thread/queue/list",{"threadId":thread,"limit":100})
        while True:
            if any(x["clientUserMessageId"]==job for x in queued["data"]):return True
            if not queued.get("nextCursor"):break
            queued=self.client.call("thread/queue/list",{"threadId":thread,"limit":100,"cursor":queued["nextCursor"]})
        view=self.client.call("thread/read",{"threadId":thread,"includeTurns":True})["thread"]
        return any(i.get("clientId")==job for t in view["turns"] for i in t["items"] if i["type"]=="userMessage")
