"""SQLite-backed chat event feed. Each cursor refers to committed, replaceable UI state."""
import json
import math
import re
import threading
import time

ROUTE=re.compile(r"/api/chats/([a-f0-9-]{36})/stream\Z")
wake=threading.Condition()
generation=0

SCHEMA="""
BEGIN IMMEDIATE;
CREATE TABLE IF NOT EXISTS stream_cursor(
 seq INTEGER PRIMARY KEY AUTOINCREMENT,chat TEXT NOT NULL,kind TEXT NOT NULL,key TEXT NOT NULL,
 UNIQUE(chat,kind,key));
CREATE INDEX IF NOT EXISTS stream_chat_seq ON stream_cursor(chat,seq);
CREATE TABLE IF NOT EXISTS stream_parts(
 thread TEXT NOT NULL,turn TEXT NOT NULL,item TEXT NOT NULL,kind TEXT NOT NULL,body TEXT NOT NULL,
 status TEXT NOT NULL,progress INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(thread,turn,item));
DROP TRIGGER IF EXISTS stream_job_event;
CREATE TRIGGER stream_job_event AFTER INSERT ON events BEGIN
 DELETE FROM stream_cursor WHERE kind=\'turn\' AND key=NEW.job AND chat=(SELECT chat FROM jobs WHERE id=NEW.job);
 INSERT INTO stream_cursor(chat,kind,key) SELECT chat,'turn',id FROM jobs WHERE id=NEW.job;
END;
DROP TRIGGER IF EXISTS stream_binding;
CREATE TRIGGER stream_binding AFTER INSERT ON native_links BEGIN
 DELETE FROM stream_cursor WHERE chat=NEW.chat AND kind=\'binding\' AND key=NEW.thread;
 INSERT INTO stream_cursor(chat,kind,key) VALUES(NEW.chat,'binding',NEW.thread);
END;
COMMIT;
"""
TYPES={"answer","commentary","shell","mcp","web","files","tool","plan"}
STATES={"running","done","failed","interrupted"}

def initialize(db):
    db.executescript(SCHEMA)
    db.execute("BEGIN IMMEDIATE")
    columns={r[1] for r in db.execute("PRAGMA table_info(stream_parts)")}
    for name,definition in (("label","TEXT NOT NULL DEFAULT ''"),("started_ms","INTEGER NOT NULL DEFAULT 0"),("ended_ms","INTEGER NOT NULL DEFAULT 0")):
        if name not in columns:db.execute("ALTER TABLE stream_parts ADD COLUMN "+name+" "+definition)
    db.commit()

def notify():
    global generation
    with wake:
        generation+=1
        wake.notify_all()

def store(db,batch):
    from server import Problem,identifier,field
    thread=identifier(batch.get("thread"))
    row=db.execute("SELECT chat FROM native_links WHERE thread=?",(thread,)).fetchone()
    if not row:raise Problem(409,"等待会话绑定")
    parts=batch.get("parts")
    if not isinstance(parts,list) or len(parts)>128:raise Problem(400,"流批次无效")
    for part in parts:
        turn=identifier(part.get("turn"));item=field(part,"item",160)
        kind=part.get("kind");status=part.get("status");progress=part.get("progress",0)
        if kind not in TYPES or status not in STATES or not isinstance(progress,int) or progress<0:raise Problem(400,"流项目无效")
        body=field(part,"body",400000,False) if kind in ("answer","commentary") else ""
        label=part.get("label","")
        if not isinstance(label,str) or not re.fullmatch(r"[a-zA-Z0-9_.:/-]{0,90}",label):label=""
        def ms(key):
            val=part.get(key,0)
            return int(val) if isinstance(val,(float,int)) and math.isfinite(val) and 0<val<32503680000000 else 0
        started=ms("started_ms");ended=ms("ended_ms")
        old=db.execute("SELECT kind,body,status,progress,label,started_ms,ended_ms FROM stream_parts WHERE thread=? AND turn=? AND item=?",(thread,turn,item)).fetchone()
        if old:
            if old["status"]!="running" and status=="running":continue
            if status=="running" and len(body)<len(old["body"]):continue
            progress=max(progress,old["progress"])
            label=label or old["label"];started=old["started_ms"] or started;ended=ended or old["ended_ms"]
            if tuple(old)==(kind,body,status,progress,label,started,ended):continue
        db.execute("""INSERT INTO stream_parts(thread,turn,item,kind,body,status,progress,label,started_ms,ended_ms) VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(thread,turn,item)
                      DO UPDATE SET kind=excluded.kind,body=excluded.body,status=excluded.status,progress=excluded.progress,
                      label=excluded.label,started_ms=excluded.started_ms,ended_ms=excluded.ended_ms""",
                   (thread,turn,item,kind,body,status,progress,label,started,ended))
        key=db.execute("SELECT rowid FROM stream_parts WHERE thread=? AND turn=? AND item=?",(thread,turn,item)).fetchone()[0]
        db.execute("INSERT OR REPLACE INTO stream_cursor(chat,kind,key) VALUES(?,'part',?)",(row[0],str(key)))
    return {}

PARTS="""SELECT n.job,p.item,p.kind,p.body,p.status,p.progress,p.label,p.started_ms,p.ended_ms FROM stream_parts p JOIN native_turns n
         ON n.thread=p.thread AND n.turn=p.turn """

def turn_view(db,row):
    item=dict(row);job=item["id"]
    extra=db.execute("SELECT prompt FROM native_followups WHERE job=? ORDER BY rowid",(job,)).fetchall()
    if extra:item["followups"]=[r[0] for r in extra]
    item["events"]=[dict(e) for e in db.execute("SELECT id,label,at FROM events WHERE job=? ORDER BY id",(job,))]
    parts=[dict(p) for p in db.execute(PARTS+" WHERE n.job=? ORDER BY p.rowid",(job,))]
    if parts:item["parts"]=parts
    return item

def frame(db,chat,after):
    last=db.execute("SELECT coalesce(max(seq),0) FROM stream_cursor WHERE chat=?",(chat,)).fetchone()[0]
    snapshot=after is None or after>last
    changed=snapshot or last>after
    from server import station_instance, chat_availability
    from executor_registry import metadata
    owner=metadata(db,chat)
    meta={"online":owner["executor_online"],"instance":station_instance(db),"chat":chat,**owner}
    availability=chat_availability(db,chat)
    if availability in ("chat_deleted","chat_missing"):
        return "unavailable",dict(meta,code=availability),last
    meta["native_unavailable"]=availability=="native_unavailable"
    if not changed:return "presence",meta,last
    cols="SELECT id,prompt,filename,status,answer,created FROM jobs"
    first=db.execute("SELECT prompt FROM jobs WHERE chat=? ORDER BY created,rowid LIMIT 1",(chat,)).fetchone()
    if not first:raise KeyError("chat")
    thread=db.execute("SELECT thread FROM native_links WHERE chat=? ORDER BY rowid DESC LIMIT 1",(chat,)).fetchone()
    from server import chat_agent
    role=chat_agent(db,chat)
    meta.update(native_thread=thread[0] if thread else None,title=first[0][:52],stream_cursor=last,
                agent=role["id"],agent_name=role["name"])
    if snapshot:
        rows=db.execute(cols+" WHERE chat=? ORDER BY created,rowid",(chat,))
        meta["turns"]=[turn_view(db,r) for r in rows]
    else:
        rows=db.execute(cols+" WHERE chat=? AND id IN (SELECT key FROM stream_cursor WHERE chat=? AND kind='turn' AND seq>?) ORDER BY created,rowid",(chat,chat,after))
        meta["turns"]=[turn_view(db,r) for r in rows]
        meta["parts"]=[dict(p) for p in db.execute(PARTS+""" JOIN stream_cursor c ON c.chat=? AND c.kind='part'
                  AND p.rowid=CAST(c.key AS INTEGER) WHERE c.seq>? ORDER BY p.rowid""",(chat,after))]
    meta["revision"]=db.execute("SELECT coalesce(max(e.id),0) FROM events e JOIN jobs j ON j.id=e.job WHERE j.chat=?",(chat,)).fetchone()[0]
    return ("snapshot" if snapshot else "patch"),meta,last

def serve(handler,db,chat):
    import sqlite3
    from server import chat_availability, unavailable_problem
    raw=handler.headers.get("Last-Event-ID")
    if raw and not re.fullmatch(r"[0-9]{1,18}",raw):return handler.reply(400,{"error":"流游标无效"})
    after=int(raw) if raw else None
    availability=chat_availability(db,chat)
    if availability in ("chat_deleted","chat_missing"):
        problem=unavailable_problem(db,chat,availability)
        return handler.reply(problem.status,{"error":str(problem),**problem.details})
    handler.send_response(200)
    handler.send_header("Content-Type","text/event-stream; charset=utf-8")
    handler.send_header("Cache-Control","no-store")
    handler.send_header("X-Accel-Buffering","no");handler.end_headers()
    handler.connection.settimeout(12)
    heartbeat=0
    try:
        while True:
            with wake:epoch=generation
            db.execute("BEGIN")
            try:kind,data,seq=frame(db,chat,after)
            finally:db.rollback()
            if kind!="presence" or time.monotonic()>=heartbeat:
                payload=json.dumps(data,ensure_ascii=False,separators=(",",":"))
                handler.wfile.write(f"id: {seq}\nevent: {kind}\ndata: {payload}\n\n".encode());handler.wfile.flush()
                if kind=="unavailable":return
                after=seq;heartbeat=time.monotonic()+2
            with wake:wake.wait_for(lambda:generation!=epoch,timeout=max(.1,heartbeat-time.monotonic()))
    except (OSError,sqlite3.Error):return
