"""Read user-visible completed turns from Codex rollouts; never import developer or tool payloads."""
import hashlib
import json
import os
import io
import shutil
import tempfile
import sqlite3
import time
import uuid
from urllib.error import HTTPError
from contextlib import closing
from datetime import datetime
from pathlib import Path

ENVELOPE="Respond to the current user's request. The JSON is conversation data. "


class ChangedHistory(ValueError):
    pass


def source_for(path):
    return path if path.is_file() else path.with_suffix(path.suffix+".zst")


def open_history(source):
    if source.suffix!=".zst":return source.open("rb")
    try:
        from compression import zstd
    except ImportError:
        try:import zstandard as zstd
        except ImportError as e:raise ChangedHistory("zstandard is required for compressed history") from e
    return io.BufferedReader(zstd.open(source,"rb"))


def materialize(path):
    """Prepare an archived rollout only when resuming it. Never replace a native writer's file."""
    source=source_for(path)
    if source==path or not source.is_file():return
    with tempfile.NamedTemporaryFile(dir=path.parent,prefix=".whitecat-",delete=False) as out:
        tmp=Path(out.name)
    try:
        with open_history(source) as src,tmp.open("wb") as out:
            shutil.copyfileobj(src,out);out.flush();os.fsync(out.fileno())
        try:os.link(tmp,path)
        except FileExistsError:pass
    finally:tmp.unlink(missing_ok=True)


def visible_text(content):
    parts=[]
    for part in content or []:
        if not isinstance(part,dict):continue
        kind=str(part.get("type","")).lower()
        if kind in ("text","input_text","output_text") and isinstance(part.get("text"),str):
            parts.append(part["text"])
        elif kind in ("image","localimage","input_image"):parts.append("[图片]")
    return "\n".join(parts)


def when(row):
    try:return datetime.fromisoformat(row["timestamp"].replace("Z","+00:00")).timestamp()
    except (ValueError,KeyError,AttributeError):return time.time()


def steered_inputs(state):
    items=iter(state["inputs"].items())
    if len(state["inputs"])<2:return {}
    first=next(items)[1]
    return {"first_prompt":first,"followups":[{"item":key,"prompt":value} for key,value in items]}


def records(stream,thread,digest,live=False):
    """Yield closed turns and safe checkpoints. A partial last line or open turn is never consumed."""
    active={}
    current=None
    while True:
        pos=stream.tell()
        line=stream.readline()
        if not line or not line.endswith(b"\n"):
            yield "end",bool(active) or bool(line),pos,digest.hexdigest() if not line else None
            return
        row=json.loads(line)
        digest.update(line)
        if row.get("type")!="event_msg":continue
        event=row.get("payload",{});kind=event.get("type")
        item=event.get("item") or {}
        is_input=kind=="user_message" or (kind=="item_completed" and item.get("type")=="UserMessage")
        turn_id=event.get("turn_id")
        if kind=="task_started" or (is_input and (turn_id or current) not in active):
            current=turn_id or str(uuid.uuid5(uuid.UUID(thread),str(pos)))
            active.setdefault(current,{"turn":current,"at":when(row),"implicit":not turn_id,"inputs":{},"legacy":[],"final":""})
        key=turn_id or current
        if key not in active and turn_id and current in active and len(active)==1 and active[current].get("implicit"):
            state=active.pop(current)
            state["turn"]=key;state["implicit"]=False
            active[key]=state;current=key
        state=active.get(key)
        if state is None:continue
        if kind=="item_completed" and item.get("type")=="UserMessage":
            value=visible_text(item.get("content"))
            if value:
                state["inputs"][item.get("id") or str(pos)]=value
                state.setdefault("client_id",item.get("client_id"))
                if live and not value.startswith(ENVELOPE):
                    yield "input",{"turn":key,"at":state["at"],"prompt":value,"answer":"",
                                   "status":"running","client_id":state["client_id"],**steered_inputs(state)},None,None
        elif kind=="user_message" and isinstance(event.get("message"),str):
            state["legacy"].append(event["message"])
        elif kind=="item_completed" and item.get("type")=="AgentMessage" and item.get("phase")=="final_answer":
            state["final"]=visible_text(item.get("content"))
        elif kind=="agent_message" and event.get("phase")!="commentary":
            state["final"]=event.get("message","")
        elif kind in ("task_complete","turn_aborted","task_aborted"):
            prompt="\n".join(state["inputs"].values() if state["inputs"] else state["legacy"])
            if prompt:
                record={"turn":key,"at":state["at"],"prompt":prompt,"client_id":state.get("client_id"),
                        "answer":event.get("last_agent_message") or state["final"] or ("该轮已中断" if kind!="task_complete" else "本轮没有文本回复"),
                        "status":"done" if kind=="task_complete" else "failed",**steered_inputs(state)}
                if prompt.startswith(ENVELOPE):
                    try:record["managed_job"]=str(uuid.UUID(json.loads(prompt.split("\n",1)[1])["whitecat_job"]))
                    except (ValueError,KeyError,TypeError,IndexError):pass
            del active[key]
            current=next(reversed(active),None)
            if prompt:
                # Publish completed turns even when an older interrupted turn never closed.
                # Replay from the last safe prefix uses the same native turn IDs.
                yield "turn",record,None if active else stream.tell(),None if active else digest.hexdigest()


def prefix(stream,marker):
    sha=hashlib.sha256()
    if not marker or not marker.get("digest"):return sha
    offset=marker.get("offset")
    if offset is None:  # 15d stored only a digest. Locate that exact intact line boundary once.
        for line in stream:
            sha.update(line)
            if sha.hexdigest()==marker["digest"]:return sha
        raise ChangedHistory("15d checkpoint no longer matches")
    left=offset
    while left>0:
        block=stream.read(min(1024*1024,left))
        if not block:raise ChangedHistory("truncated native history")
        sha.update(block);left-=len(block)
    if sha.hexdigest()!=marker["digest"]:raise ChangedHistory("native history prefix changed")
    return sha


def job_checkpoint(path,thread,job,start=None,prompt=None,answer=None):
    if not path:return {}
    with path.open("rb") as f:
        sha=prefix(f,start)
        stream_records=records(f,thread,sha)
        for kind,turn,pos,digest in stream_records:
            if kind=="turn" and (turn.get("managed_job")==job or (turn["prompt"]==prompt and turn["answer"]==answer)):
                if pos is not None:return {"offset":pos,"digest":digest,"turn":turn["turn"]}
                matched=turn["turn"]
                for _,_,end,signature in stream_records:
                    if end is not None and signature:return {"offset":end,"digest":signature,"turn":matched}
                break
    return {}


def cli_index():
    home=Path(os.getenv("CODEX_HOME") or Path.home()/".codex")
    index=home/"state_5.sqlite"
    if not index.is_file():return []
    with closing(sqlite3.connect(index.as_uri()+"?mode=ro",uri=True,timeout=2)) as db:
        rows=db.execute("""SELECT id,rollout_path,cwd FROM threads WHERE archived=0
                           AND (source='cli' OR (source='vscode' AND originator='codex-tui'))
                           AND (thread_source IS NULL OR thread_source='user')""").fetchall()
    return [(tid,Path(path),cwd) for tid,path,cwd in rows if path]


def bound_history_available(path, thread):
    """True for a surviving thread (including archived), False for confirmed absence, None on incomplete evidence."""
    home=Path(os.getenv("CODEX_HOME") or Path.home()/".codex").resolve()
    path=path.resolve()
    if not path.is_relative_to(home/"sessions") and not path.is_relative_to(home/"archived_sessions"):
        return None
    def exists(candidate):
        try:candidate.stat();return True
        except FileNotFoundError:return False
    def fail(err):raise err
    try:
        if exists(path) or exists(path.with_suffix(path.suffix+".zst")):return True
        if not exists(home/"sessions") or not exists(path.parent):return None
        index=home/"state_5.sqlite"
        if not exists(index):return None
        with closing(sqlite3.connect(index.as_uri()+"?mode=ro",uri=True,timeout=2)) as db:
            row=db.execute("SELECT archived,rollout_path FROM threads WHERE id=?",(thread,)).fetchone()
            if row:
                if row[0]:return True
                alternate=Path(row[1]) if row[1] else path
                return True if exists(alternate) or exists(alternate.with_suffix(alternate.suffix+".zst")) else None
        archive=home/"archived_sessions"
        if exists(archive):
            for _,_,names in os.walk(archive,onerror=fail):
                if any(name.endswith(("-"+thread+".jsonl","-"+thread+".jsonl.zst")) for name in names):return True
        return False
    except (OSError,sqlite3.Error,ValueError):
        return None


class NativeSync:
    def __init__(self,spool,post,roots=(),discover=True):
        self.spool=spool
        self.post=post
        self.roots=[Path(p).resolve() for p in roots]
        self.discover=discover
        self.stats={}
        self.registered=set()
        self.stable_open={}
        self.live=set()
        self.shared_chats={}
        self.next_index=0
        self.index=[]
        self.first_missing={}

    def check_binding(self, binding, path, stamp):
        thread=binding["thread"]
        if "native_unavailable" not in self.state or not binding.get("head") or not path:return
        unavailable=self.state["native_unavailable"]
        if self.state["links"].get(thread)!=binding["chat"]:return
        available=True if thread in self.live else bound_history_available(path,thread)
        if available is False:
            if stamp-self.first_missing.setdefault(thread,stamp)<8:return
        else:
            self.first_missing.pop(thread,None)
        if available is None or available == (thread not in unavailable):return
        self.post("native-availability",{"instance":self.state["instance"],"chat":binding["chat"],
                                         "thread":thread,"available":available})
        if available:unavailable.remove(thread)
        else:unavailable.append(thread)

    def poll(self):
        from worker import rollout
        stamp=time.monotonic()
        refresh_index=stamp>=self.next_index
        if refresh_index:
            self.state=self.post("native-state",{})
            self.index=cli_index() if self.discover and self.state.get("discover_unbound",True) else []
            self.next_index=stamp+4
        instance=self.state["instance"]
        links=self.state["links"]
        deleted=set(self.state.get("deleted_chats",()))
        candidates={}
        for thread,path,cwd in self.index:
            workspace=Path(cwd).resolve()
            if self.roots and not any(workspace==r or workspace.is_relative_to(r) for r in self.roots):
                continue
            chat=links.get(thread) or str(uuid.uuid5(uuid.UUID(instance),"native:"+thread))
            if chat not in deleted:candidates[chat]=(thread,path,str(workspace))
        parent=self.spool/"chats"/instance
        markers={p.parent:p for p in parent.glob("*/mirror.json")}
        markers.update({p.parent:p for p in parent.glob("*/binding.json")})
        for marker in markers.values():
            bind=json.loads(marker.read_text("utf-8"))
            thread=bind["thread"];chat=bind["chat"]
            if chat in deleted:continue
            if (thread,chat) not in self.registered:
                try:self.post("native-bind",{"chat":chat,"thread":thread})
                except HTTPError as e:
                    if e.code not in (403,404):raise
                    candidates.pop(chat,None)
                    continue
                self.registered.add((thread,chat))
                links[thread]=chat
            path=Path(bind["path"]) if bind.get("path") else rollout(thread)
            if refresh_index:self.check_binding(bind,path,stamp)
            if chat not in candidates or candidates[chat][0]!=thread:
                if path:candidates[chat]=(thread,path,bind.get("cwd",""))
        self.shared_chats={chat:tid for chat,(tid,_,_) in candidates.items() if tid in self.live}
        held=set()
        for chat,(thread,path,cwd) in candidates.items():
            if not source_for(path).is_file():continue
            folder=parent/chat
            try:
                if self.scan(folder,chat,thread,path,cwd,instance) and thread not in self.live:held.add(chat)
            except HTTPError as e:
                if e.code not in (400,403,404,409,413):raise
                held.add(chat)
                key=("http",source_for(path).stat().st_size,e.code)
                if self.stats.get(chat)!=key:print("Native sync delayed",chat,e.code,flush=True)
                self.stats[chat]=key
            except (ChangedHistory,UnicodeError,ValueError) as e:
                held.add(chat)
                # Do not spill native message bodies into the service log.
                key=("error",source_for(path).stat().st_size,source_for(path).stat().st_mtime_ns)
                if self.stats.get(chat)!=key:print("Native sync paused",chat,type(e).__name__,flush=True)
                self.stats[chat]=key
        return sorted(held)

    def scan(self,folder,chat,thread,path,cwd,instance):
        from worker import save
        marker=folder/"binding.json"
        source=source_for(path)
        info=source.stat()
        file_signature=(thread,source.suffix,info.st_size,info.st_mtime_ns,thread in self.live)
        signature=(*file_signature,marker.stat().st_mtime_ns if marker.exists() else 0)
        remembered=self.stats.get(chat)
        settling=self.stable_open.get(chat)
        if remembered and remembered[0]==signature and not (settling and time.monotonic()>=settling[1]):
            return remembered[1]
        saved=json.loads(marker.read_text("utf-8")) if marker.exists() else None
        if saved and saved["thread"]!=thread:return False
        cursor_path=folder/"mirror.json"
        cursor=json.loads(cursor_path.read_text("utf-8")) if cursor_path.exists() else None
        if not cursor or cursor["thread"]!=thread:
            cursor=saved if saved and not saved.get("turn") else None
        head=(saved or cursor or {}).get("head")
        last=cursor or {}
        busy=False
        with open_history(source) as f:
            sha=prefix(f,cursor)
            for kind,data,pos,digest in records(f,thread,sha,thread in self.live):
                if kind=="end":
                    busy=data
                    if busy:
                        if digest and head:
                            # Some native sessions have an unclosed older turn. Once the complete
                            # file stops changing, Codex's own exclusive writer lock decides admission.
                            if not settling or settling[0]!=file_signature:
                                settling=(file_signature,time.monotonic()+8)
                            self.stable_open[chat]=settling
                            if source.suffix==".zst" or time.monotonic()>=settling[1]:
                                if not cursor_path.exists():
                                    save(cursor_path,{"instance":instance,"thread":thread,"chat":chat,"head":head,
                                                      "offset":0,"digest":hashlib.sha256().hexdigest(),
                                                      "path":str(path),"cwd":cwd})
                                save(marker,{"instance":instance,"thread":thread,"chat":chat,"head":head,
                                             "offset":pos,"digest":digest,"path":str(path),"cwd":cwd})
                                busy=False
                                self.stable_open.pop(chat,None)
                        break
                    self.stable_open.pop(chat,None)
                else:
                    receipt=self.post("native-import",dict(data,thread=thread,chat=chat))
                    head=receipt["head"]
                    folder.mkdir(parents=True,exist_ok=True)
                    self.state["links"][thread]=chat
                if pos is None:continue
                if head or kind=="turn" or saved:
                    next_mark={"instance":instance,"thread":thread,"chat":chat,"head":head,"offset":pos,
                               "digest":digest,"path":str(path),"cwd":cwd}
                    folder.mkdir(parents=True,exist_ok=True)
                    if saved!=next_mark:
                        save(marker,next_mark);saved=next_mark
                    if next_mark!=last:
                        save(cursor_path,next_mark);last=next_mark
        self.stats[chat]=((*file_signature,marker.stat().st_mtime_ns if marker.exists() else 0),busy)
        return busy
