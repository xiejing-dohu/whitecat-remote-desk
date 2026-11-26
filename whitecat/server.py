"""Whitecat single-user API. Operator access is local or via authenticated SSH forwarding."""
import sys
if __name__=="__main__":
    # Helpers importing server must share the handler's exception classes.
    sys.modules.setdefault("server",sys.modules[__name__])
import stream_store
import executor_registry as registry
import argparse
import hashlib
import json
import os
import secrets
import sqlite3
import time
import uuid
from contextlib import closing
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parent
STATE = Path(os.getenv("WHITECAT_STATE") or Path(os.getenv("LOCALAPPDATA") or Path.home()) / "WhitecatDesk")
DB = STATE / "desk.sqlite"
ASSETS = {"/": ("index.html","text/html"), "/app.js": ("app.js","text/javascript"),
          "/style.css": ("style.css","text/css"), "/cat.png": ("cat.png","image/png")}


class Problem(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status
        self.details = {}


def uid():
    return str(uuid.uuid4())


def identifier(value):
    try:
        return str(uuid.UUID(value))
    except (ValueError, TypeError, AttributeError):
        raise Problem(400, "编号无效") from None


def field(body, name, limit, required=True):
    value = body.get(name, "")
    if not isinstance(value,str) or len(value)>limit or (required and not value.strip()):
        raise Problem(400, "字段无效：" + name)
    return value


def init():
    STATE.mkdir(parents=True, exist_ok=True)
    with closing(sqlite3.connect(DB)) as db, db:
        db.executescript("""
        PRAGMA journal_mode=WAL;
        CREATE TABLE IF NOT EXISTS jobs(
          id TEXT PRIMARY KEY, chat TEXT NOT NULL, command TEXT NOT NULL UNIQUE, digest TEXT NOT NULL,
          prompt TEXT NOT NULL, filename TEXT NOT NULL, sample TEXT NOT NULL, created REAL NOT NULL,
          status TEXT NOT NULL, ticket TEXT UNIQUE, answer TEXT NOT NULL DEFAULT '');
        CREATE INDEX IF NOT EXISTS jobs_chat ON jobs(chat,created);
        CREATE TABLE IF NOT EXISTS events(id INTEGER PRIMARY KEY, job TEXT NOT NULL, event_key TEXT NOT NULL,
          label TEXT NOT NULL, at REAL NOT NULL, UNIQUE(job,event_key));
        CREATE TABLE IF NOT EXISTS native_links(thread TEXT PRIMARY KEY,chat TEXT NOT NULL);
        CREATE INDEX IF NOT EXISTS native_chat ON native_links(chat);
        CREATE TABLE IF NOT EXISTS native_turns(thread TEXT NOT NULL,turn TEXT NOT NULL,job TEXT NOT NULL UNIQUE,
          PRIMARY KEY(thread,turn));
        CREATE TABLE IF NOT EXISTS native_followups(job TEXT NOT NULL,item TEXT NOT NULL,prompt TEXT NOT NULL,
          PRIMARY KEY(job,item));
        CREATE TABLE IF NOT EXISTS live_jobs(job TEXT PRIMARY KEY,thread TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS agents(
          id TEXT PRIMARY KEY, name TEXT NOT NULL, instructions TEXT NOT NULL, archived INTEGER NOT NULL DEFAULT 0);
        CREATE TABLE IF NOT EXISTS chat_agents(
          chat TEXT PRIMARY KEY, agent TEXT NOT NULL, name TEXT NOT NULL, instructions TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS station_config(name TEXT PRIMARY KEY,value TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS deleted_chats(chat TEXT PRIMARY KEY);
        CREATE TABLE IF NOT EXISTS native_unavailable(thread TEXT PRIMARY KEY);
        CREATE TRIGGER IF NOT EXISTS record_chat_deletion AFTER DELETE ON jobs
          WHEN NOT EXISTS(SELECT 1 FROM jobs WHERE chat=old.chat)
          BEGIN INSERT OR IGNORE INTO deleted_chats VALUES(old.chat); END;
        CREATE TRIGGER IF NOT EXISTS clear_chat_deletion AFTER INSERT ON jobs
          BEGIN DELETE FROM deleted_chats WHERE chat=new.chat; END;
        CREATE TABLE IF NOT EXISTS station(id INTEGER PRIMARY KEY CHECK(id=1), last_seen REAL NOT NULL);
        """)
        stream_store.initialize(db)
        registry.initialize(db)
        db.execute("INSERT OR IGNORE INTO station_config VALUES('instance',?)",(uid(),))
    keyfile = STATE / "worker.key"
    if not keyfile.exists():
        with os.fdopen(os.open(keyfile,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600),"w") as f:
            f.write(secrets.token_urlsafe(40))
    key=keyfile.read_text().strip()
    if not key:raise RuntimeError("默认工作站凭据为空")
    with closing(sqlite3.connect(DB,timeout=10)) as db,db:
        db.execute("UPDATE executors SET key_hash=? WHERE id='default'",(registry.key_hash(key),))
    return key


def station_instance(db):
    return db.execute("SELECT value FROM station_config WHERE name='instance'").fetchone()[0]


def chat_availability(db, chat):
    if not db.execute("SELECT 1 FROM jobs WHERE chat=?", (chat,)).fetchone():
        recorded = db.execute("SELECT 1 FROM deleted_chats WHERE chat=?", (chat,)).fetchone()
        return "chat_deleted" if recorded else "chat_missing"
    if db.execute("""SELECT 1 FROM native_unavailable WHERE thread=(
            SELECT thread FROM native_links WHERE chat=? ORDER BY rowid DESC LIMIT 1)""", (chat,)).fetchone():
        return "native_unavailable"
    return ""


def unavailable_problem(db, chat, code):
    native = code == "native_unavailable"
    error = Problem(409 if native else 404, "原生历史不可用" if native else "会话不存在")
    error.details = {"code":code, "instance":station_instance(db), "chat":chat}
    return error


def event(db, job, key, label):
    db.execute("INSERT OR IGNORE INTO events(job,event_key,label,at) VALUES(?,?,?,?)",(job,key,label,time.time()))


DEFAULT_AGENT = {"id":"codex", "name":"Codex", "instructions":"", "archived":False, "builtin":True}


def public_agent(row):
    return {**dict(row), "archived":bool(row["archived"]), "builtin":False}


def chat_agent(db, chat):
    row = db.execute("SELECT agent AS id,name,instructions FROM chat_agents WHERE chat=?", (chat,)).fetchone()
    return dict(row) if row else DEFAULT_AGENT


def agent_catalog(db):
    rows = db.execute("SELECT id,name,instructions,archived FROM agents ORDER BY archived,name COLLATE NOCASE,id")
    return {"agents":[DEFAULT_AGENT, *(public_agent(row) for row in rows)]}


def manage_agent(db, body, resource=None):
    creating = resource is None
    if resource == "codex":
        raise Problem(409, "内置 Codex 使用默认配置")
    aid = identifier(body.get("id") if creating else resource)
    row = db.execute("SELECT * FROM agents WHERE id=?", (aid,)).fetchone()
    if not creating and not row:
        raise Problem(404, "智能体不存在")
    if not creating and set(body) == {"archived"}:
        if body["archived"] is not True:
            raise Problem(400, "归档请求无效")
        db.execute("UPDATE agents SET archived=1 WHERE id=?", (aid,))
    else:
        if set(body) != ({"id","name","instructions"} if creating else {"name","instructions"}):
            raise Problem(400, "智能体配置字段无效")
        name = field(body,"name",64).strip()
        instructions = field(body,"instructions",4000).strip()
        if any(ord(char) < 32 for char in name) or "\x00" in instructions:
            raise Problem(400, "名称或角色指令包含无效字符")
        if creating and row:
            if (row["name"],row["instructions"]) != (name,instructions):
                raise Problem(409, "智能体编号已用于其他配置")
            return 200, {"agent":public_agent(row)}
        if creating:
            db.execute("INSERT INTO agents(id,name,instructions) VALUES(?,?,?)", (aid,name,instructions))
        elif row["archived"]:
            raise Problem(409, "已归档的智能体无法编辑")
        else:
            db.execute("UPDATE agents SET name=?,instructions=? WHERE id=?", (name,instructions,aid))
    updated = db.execute("SELECT * FROM agents WHERE id=?", (aid,)).fetchone()
    return 201 if creating else 200, {"agent":public_agent(updated)}


def submit(db, body):
    command = identifier(body.get("command"))
    chat = body.get("chat")
    if chat is not None:
        chat = identifier(chat)
        if "expected_instance" in body and identifier(body["expected_instance"])!=station_instance(db):
            problem=Problem(409,"工作站实例已更换，请核实会话后重新发送")
            problem.details={"code":"station_changed","instance":station_instance(db),"chat":chat}
            raise problem
    prompt = field(body,"prompt",10000)
    filename = field(body,"filename",150,False)
    sample = field(body,"sample",32768,False)
    if sample and not filename:
        raise Problem(400,"请提供附件名")
    assigned=registry.owner(db,chat) if chat else registry.DEFAULT
    executor=body.get("executor",assigned)
    if not isinstance(executor,str) or not registry.ID.fullmatch(executor):raise Problem(400,"执行 Agent 编号无效")
    if chat and executor!=assigned:raise Problem(409,"已有会话固定使用原执行 Agent")
    if not db.execute("SELECT 1 FROM executors WHERE id=?",(executor,)).fetchone():raise Problem(404,"执行 Agent 不存在")
    role = chat_agent(db,chat) if chat else DEFAULT_AGENT
    requested = body.get("agent",role["id"])
    if requested != "codex":
        requested = identifier(requested)
    if chat and requested != role["id"]:
        raise Problem(409, "已有会话固定使用创建时的智能体")
    payload = [chat,prompt,filename,sample]
    if requested != "codex":
        payload.append(requested)
    if executor!=registry.DEFAULT:payload.append({"executor":executor})
    digest = hashlib.sha256(json.dumps(payload,ensure_ascii=False).encode()).hexdigest()
    saved = db.execute("SELECT id,chat,digest FROM jobs WHERE command=?", (command,)).fetchone()
    if saved:
        if saved["digest"] != digest:
            raise Problem(409,"发送编号已用于另一条消息")
        saved_role = chat_agent(db,saved["chat"])
        return 200, {"job":saved["id"],"chat":saved["chat"],"agent":saved_role["id"],"agent_name":saved_role["name"],**registry.receipt_owner(db,executor)}
    if chat:
        last=db.execute("SELECT status FROM jobs WHERE chat=? ORDER BY created DESC LIMIT 1",(chat,)).fetchone()
        availability = chat_availability(db, chat)
        if availability:
            raise unavailable_problem(db, chat, availability)
        if last["status"] in ("queued","running") and not db.execute(
                "SELECT 1 FROM native_links WHERE chat=?",(chat,)).fetchone():
            raise Problem(409,"请等待本轮结束")
    if not chat and requested != "codex":
        agent = db.execute("SELECT id,name,instructions,archived FROM agents WHERE id=?", (requested,)).fetchone()
        if not agent:
            raise Problem(404, "智能体不存在")
        if agent["archived"]:
            raise Problem(409, "智能体已归档，请选择其他智能体")
        role = dict(agent)
        chat = uid()
        db.execute("INSERT INTO chat_agents(chat,agent,name,instructions) VALUES(?,?,?,?)",
                   (chat,role["id"],role["name"],role["instructions"]))
    job, chat = uid(), chat or uid()
    db.execute("INSERT OR IGNORE INTO chat_executors VALUES(?,?)",(chat,executor))
    db.execute("INSERT INTO jobs(id,chat,command,digest,prompt,filename,sample,created,status) VALUES(?,?,?,?,?,?,?,?,?)",
               (job,chat,command,digest,prompt,filename,sample,time.time(),"queued"))
    event(db,job,"accepted","已加入白猫队列")
    return 201, {"job":job,"chat":chat,"agent":role["id"],"agent_name":role["name"],**registry.receipt_owner(db,executor)}


def claim(db,ticket,held=(),live=(),agent_roles=False,executor=registry.DEFAULT):
    row=db.execute("SELECT * FROM jobs WHERE ticket=?",(ticket,)).fetchone()
    if row and registry.owner(db,row["chat"])!=executor:raise Problem(403,"工作站回执无效")
    if not row:
        candidates=db.execute("""SELECT j.* FROM jobs j JOIN chat_executors x ON x.chat=j.chat
            WHERE j.status='queued' AND x.executor=?
            AND NOT EXISTS(SELECT 1 FROM native_unavailable WHERE thread=(
                SELECT thread FROM native_links WHERE chat=j.chat ORDER BY rowid DESC LIMIT 1))
            AND (? OR NOT EXISTS(SELECT 1 FROM chat_agents a WHERE a.chat=j.chat))
            ORDER BY j.created,j.rowid""", (executor,agent_roles))
        active={r[0] for r in db.execute("SELECT DISTINCT chat FROM jobs WHERE status='running'")}
        held=set(held) | (active-set(live))
        row=next((candidate for candidate in candidates if candidate["chat"] not in held),None)
        if not row:
            return {}
        db.execute("UPDATE jobs SET status='running',ticket=? WHERE id=?",(ticket,row["id"]))
        event(db,row["id"],"claimed","Codex 工作站已领取")
    elif row["status"] != "running" or (not agent_roles and chat_agent(db,row["chat"])["id"] != "codex"):
        return {}
    history=db.execute("""SELECT prompt,answer,filename,sample FROM jobs WHERE chat=? AND created<? AND status='done'
                         ORDER BY created DESC LIMIT 6""",(row["chat"],row["created"])).fetchall()
    parent=db.execute("SELECT id FROM jobs WHERE chat=? AND created<? AND status='done' ORDER BY created DESC LIMIT 1",(row["chat"],row["created"])).fetchone()
    instance=db.execute("SELECT value FROM station_config WHERE name='instance'").fetchone()[0]
    return {"id":row["id"],"chat":row["chat"],"instance":instance,"parent":parent[0] if parent else None,"prompt":row["prompt"],"filename":row["filename"],"sample":row["sample"],
            "executor":executor,"agent":chat_agent(db,row["chat"]), "history":[dict(item) for item in reversed(history)]}


def link_native(db,thread,chat):
    db.execute("INSERT OR IGNORE INTO native_links VALUES(?,?)",(thread,chat))
    if db.execute("SELECT chat FROM native_links WHERE thread=?",(thread,)).fetchone()[0]!=chat:
        raise Problem(409,"原生线程已绑定其他会话")


def import_native(db,body):
    thread=identifier(body.get("thread"))
    chat=identifier(body.get("chat"))
    turn=identifier(body.get("turn"))
    if db.execute("SELECT 1 FROM deleted_chats WHERE chat=?", (chat,)).fetchone():
        raise unavailable_problem(db,chat,"chat_deleted")
    link_native(db,thread,chat)
    saved=db.execute("SELECT job FROM native_turns WHERE thread=? AND turn=?",(thread,turn)).fetchone()
    def receipt(job):
        for follow in body.get("followups",[]):
            key=field(follow,"item",160);text=field(follow,"prompt",400000)
            inserted=db.execute("INSERT OR IGNORE INTO native_followups VALUES(?,?,?)",(job,key,text))
            if inserted.rowcount:event(db,job,"native.input."+key,"收到终端追加消息")
        head=db.execute("SELECT id FROM jobs WHERE chat=? AND status='done' ORDER BY created DESC,id DESC LIMIT 1",(chat,)).fetchone()
        return {"job":job,"chat":chat,"head":head[0] if head else None}
    status=body.get("status")
    if status not in ("running","done","failed"):raise Problem(400,"原生轮次状态无效")
    client=body.get("client_id")
    managed=db.execute("""SELECT j.id FROM jobs j JOIN live_jobs l ON l.job=j.id
                          WHERE j.id=? AND l.thread=? AND j.chat=?""",(client,thread,chat)).fetchone() if isinstance(client,str) else None
    if saved or managed:
        jid=saved[0] if saved else managed[0]
        if saved and managed and jid!=managed[0]:raise Problem(409,"原生轮次标识不一致")
        if not saved:db.execute("INSERT INTO native_turns VALUES(?,?,?)",(thread,turn,jid))
        current=db.execute("SELECT status,digest FROM jobs WHERE id=?",(jid,)).fetchone()
        if current is None:
            # Keep a selectively removed turn out of subsequent native replays.
            head=db.execute("SELECT id FROM jobs WHERE chat=? AND status='done' ORDER BY created DESC,id DESC LIMIT 1",(chat,)).fetchone()
            return {"job":jid,"chat":chat,"head":head[0] if head else None}
        if current["status"] in ("running","uncertain") and (managed or current["digest"]=="native"):
            # A receipt can arrive after the first snapshot or after a station restart.
            at=float(body["at"])
            if not 0<at<32503680000:raise Problem(400,"原生时间无效")
            answer=field(body,"answer",400000,status!="running")
            db.execute("UPDATE jobs SET status=?,answer=?,created=? WHERE id=?",(status,answer,at,jid))
            event(db,jid,"native."+status,{"running":"原生终端正在处理","done":"原生回复已同步","failed":"原生轮次已中断"}[status])
        return receipt(jid)
    if body.get("managed_job"):
        jid=identifier(body["managed_job"])
        own=db.execute("SELECT id FROM jobs WHERE id=? AND chat=?",(jid,chat)).fetchone()
        if own:
            db.execute("INSERT INTO native_turns VALUES(?,?,?)",(thread,turn,jid))
            return receipt(jid)
    prompt=field(body,"first_prompt" if body.get("first_prompt") else "prompt",400000);answer=field(body,"answer",400000,status!="running")
    active=db.execute("""SELECT 1 FROM jobs j WHERE chat=? AND status='running' AND digest!='native'
                       AND NOT EXISTS(SELECT 1 FROM live_jobs l WHERE l.job=j.id)""",(chat,)).fetchone()
    if active:raise Problem(409,"工作站执行期间延后同步")
    queued=db.execute("SELECT MIN(created) FROM jobs WHERE chat=? AND status='queued'",(chat,)).fetchone()[0]
    at=float(body["at"])
    if not 0<at<32503680000:raise Problem(400,"原生时间无效")
    if queued is not None and at>=queued:
        db.execute("UPDATE jobs SET created=created+? WHERE chat=? AND status='queued'",(at-queued+0.00001,chat))
    job=str(uuid.uuid5(uuid.UUID(thread),"native-turn:"+turn))
    command=str(uuid.uuid5(uuid.UUID(thread),"native-command:"+turn))
    db.execute("""INSERT INTO jobs(id,chat,command,digest,prompt,filename,sample,created,status,answer)
                  VALUES(?,?,?,'native',?,'','',?,?,?)""",(job,chat,command,prompt,at,status,answer))
    db.execute("INSERT INTO native_turns VALUES(?,?,?)",(thread,turn,job))
    event(db,job,"native.import","来自原生 Codex CLI")
    return receipt(job)


def worker(db,path,body,executor=registry.DEFAULT):
    def own_chat(chat):
        if registry.owner(db,chat)!=executor:raise Problem(403,"工作站无权访问该资源")
    db.execute("UPDATE executors SET last_seen=? WHERE id=?",(time.time(),executor))
    db.execute("INSERT INTO station VALUES(1,?) ON CONFLICT(id) DO UPDATE SET last_seen=excluded.last_seen",(time.time(),))
    if path=="/worker/heartbeat":
        if executor==registry.DEFAULT and body.get("name"):
            name=field(body,"name",72)
            db.execute("INSERT INTO station_config VALUES('name',?) ON CONFLICT(name) DO UPDATE SET value=excluded.value",(name,))
        return {"executor":executor,"instance":station_instance(db)}
    if path=="/worker/native-state":
        links=dict(db.execute("""SELECT n.thread,n.chat FROM native_links n JOIN chat_executors x ON x.chat=n.chat
                                 WHERE x.executor=?""",(executor,)))
        unavailable=db.execute("""SELECT u.thread FROM native_unavailable u JOIN native_links n ON n.thread=u.thread
                                 JOIN chat_executors x ON x.chat=n.chat WHERE x.executor=?""",(executor,))
        deleted=db.execute("""SELECT d.chat FROM deleted_chats d JOIN chat_executors x ON x.chat=d.chat
                              WHERE x.executor=?""",(executor,))
        return {"instance":station_instance(db),"executor":executor,"discover_unbound":executor==registry.DEFAULT,
                "links":links,"native_unavailable":[r[0] for r in unavailable],"deleted_chats":[r[0] for r in deleted]}
    if path in ("/worker/native-bind","/worker/native-import","/worker/native-availability"):
        own_chat(identifier(body.get("chat")))
    if path=="/worker/native-availability":
        thread=identifier(body.get("thread"))
        if body.get("instance")!=station_instance(db) or not db.execute(
                "SELECT 1 FROM native_links WHERE thread=? AND chat=?", (thread,identifier(body.get("chat")))).fetchone():
            raise Problem(409,"工作站或线程绑定已改变")
        if body.get("available") is True:
            db.execute("DELETE FROM native_unavailable WHERE thread=?", (thread,))
        elif body.get("available") is False:
            db.execute("INSERT OR IGNORE INTO native_unavailable VALUES(?)", (thread,))
        else:raise Problem(400,"历史状态无效")
        return {}
    if path=="/worker/stream":
        linked=db.execute("SELECT chat FROM native_links WHERE thread=?",(identifier(body.get("thread")),)).fetchone()
        if not linked:raise Problem(409,"等待会话绑定")
        own_chat(linked[0])
        return stream_store.store(db,body)
    if path=="/worker/native-import":
        if executor!=registry.DEFAULT and not db.execute("SELECT 1 FROM native_links WHERE thread=? AND chat=?",
                (identifier(body.get("thread")),identifier(body.get("chat")))).fetchone():
            raise Problem(403,"额外执行 Agent 仅可同步已绑定的原生线程")
        return import_native(db,body)
    if path=="/worker/native-bind":
        thread=identifier(body.get("thread"));chat=identifier(body.get("chat"))
        if not db.execute("SELECT 1 FROM jobs WHERE chat=?",(chat,)).fetchone():
            raise unavailable_problem(db,chat,chat_availability(db,chat))
        link_native(db,thread,chat)
        return {}
    ticket=identifier(body.get("ticket"))
    if path=="/worker/claim":
        return claim(db,ticket,body.get('held',()),body.get('live',()),body.get('agent_roles') is True,executor)
    job=identifier(body.get("job"))
    job_chat=db.execute("SELECT chat FROM jobs WHERE id=?",(job,)).fetchone()
    if not job_chat:raise Problem(403,"工作站回执无效")
    own_chat(job_chat[0])
    row=db.execute("SELECT status,answer FROM jobs WHERE id=? AND ticket=?",(job,ticket)).fetchone()
    if not row:
        if path=="/worker/defer" and db.execute("SELECT 1 FROM jobs WHERE id=? AND ticket IS NULL AND status='queued'",(job,)).fetchone():
            return {}
        raise Problem(403,"工作站回执无效")
    if path=="/worker/live-intent":
        thread=identifier(body.get("thread"))
        if not db.execute("""SELECT 1 FROM jobs j JOIN native_links n ON n.chat=j.chat
                             WHERE j.id=? AND n.thread=?""",(job,thread)).fetchone():raise Problem(409,"会话绑定不符")
        db.execute("INSERT OR IGNORE INTO live_jobs VALUES(?,?)",(job,thread))
        if db.execute("SELECT thread FROM live_jobs WHERE job=?",(job,)).fetchone()[0]!=thread:raise Problem(409,"任务线程不符")
        return {}
    if path=="/worker/defer":
        if row["status"]=="running" and not db.execute("SELECT 1 FROM events WHERE job=? AND event_key='spawn'",(job,)).fetchone():
            db.execute("UPDATE jobs SET status='queued',ticket=NULL WHERE id=?",(job,))
        return {}
    if path=="/worker/history":
        if row["status"]!="running":raise Problem(409,"任务已结束")
        prior=db.execute("""SELECT p.id,p.prompt,p.filename,p.sample,p.answer FROM jobs p JOIN jobs current ON current.id=?
                          WHERE p.chat=current.chat AND p.created<current.created AND p.status='done' ORDER BY p.created,p.id""",(job,))
        return {"turns":[dict(r) for r in prior]}
    if path=="/worker/event":
        if row["status"]=="running":
            event(db,job,field(body,"key",150),field(body,"label",200))
    elif path=="/worker/finish":
        status=body.get("status")
        if status not in ("done","failed","uncertain"):
            raise Problem(400,"状态无效")
        answer=field(body,"answer",200000)
        if row["status"]=="running":
            db.execute("UPDATE jobs SET status=?,answer=? WHERE id=?",(status,answer,job))
            event(db,job,"finished",{"done":"回复已送达","failed":"执行失败","uncertain":"执行状态待核实"}[status])
        elif (status,answer)!=(row["status"],row["answer"]):
            raise Problem(409,"本次运行已有不同结果")
        if status=="done" and body.get("native_thread") and body.get("native_turn"):
            thread=identifier(body["native_thread"]);turn=identifier(body["native_turn"])
            chat=db.execute("SELECT chat FROM jobs WHERE id=?",(job,)).fetchone()[0]
            link_native(db,thread,chat)
            db.execute("INSERT OR IGNORE INTO native_turns VALUES(?,?,?)",(thread,turn,job))
            mapped=db.execute("SELECT job FROM native_turns WHERE thread=? AND turn=?",(thread,turn)).fetchone()
            if not mapped or mapped[0]!=job:raise Problem(409,"原生轮次对应另一条消息")
    else:
        raise Problem(404,"路径不存在")
    return {}


class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args):
        pass

    def reply(self,status,data,mime="application/json; charset=utf-8",attachment=False):
        if not isinstance(data,bytes):
            data=json.dumps(data,ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type",mime)
        self.send_header("Content-Length",str(len(data)))
        self.send_header("Cache-Control","no-store")
        self.send_header("X-Content-Type-Options","nosniff")
        self.send_header("Content-Security-Policy","default-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'")
        if attachment:
            self.send_header("Content-Disposition",'attachment; filename="whitecat-reply.txt"')
        self.end_headers()
        self.wfile.write(data)

    def route(self,method):
        host=self.headers.get("Host","")
        allowed={f"{h}:{self.server.server_port}" for h in ("127.0.0.1","localhost")}
        if host not in allowed or self.headers.get("Origin","http://"+host)!="http://"+host:
            raise Problem(403,"请通过本机或 SSH 隧道访问")
        path=urlsplit(self.path).path
        if getattr(self.server,"worker_only",False) and not path.startswith("/worker/"):
            raise Problem(403,"该端口仅接受工作站请求")
        if method=="GET" and path in ASSETS:
            name,mime=ASSETS[path]
            return self.reply(200,(ROOT/"web"/name).read_bytes(),mime+"; charset=utf-8" if mime.startswith("text") else mime)
        if path.startswith("/api/"):
            if self.headers.get("X-Whitecat")!="desk":
                raise Problem(403,"缺少工作台请求标记")
        elif not path.startswith("/worker/"):
            raise Problem(404,"路径不存在")
        with closing(sqlite3.connect(DB,timeout=10)) as db, db:
            db.row_factory=sqlite3.Row
            executor=None
            if path.startswith("/worker/"):
                executor=registry.authenticate(db,self.headers.get("Authorization",""))
                if not executor:raise Problem(403,"工作站凭据无效")
            if method=="POST":
                if self.headers.get_content_type()!="application/json":
                    raise Problem(415,"需要 JSON")
                length=int(self.headers.get("Content-Length","0"))
                if not 0<length<=1500000:
                    raise Problem(413,"请求大小超出范围")
                body=json.loads(self.rfile.read(length))
                if not isinstance(body,dict):
                    raise Problem(400,"需要 JSON 对象")
                db.execute("BEGIN IMMEDIATE")
                if path=="/api/jobs":
                    status,result=submit(db,body)
                elif path=="/api/admin/executors":
                    if set(body)!={"id","name"} or not isinstance(body.get("id"),str):raise Problem(400,"需要执行 Agent 的 ID 和名称")
                    port=getattr(self.server,"worker_port",self.server.server_port)
                    try:
                        result=registry.enroll(db,body["id"],body["name"],STATE/"executors"/body["id"],f"http://127.0.0.1:{port}")
                    except registry.EnrollmentError as exc:raise Problem(exc.status,str(exc)) from None
                    except (OSError,RuntimeError):raise Problem(503,"无法创建私有工作站配置目录") from None
                    status=201
                elif path=="/api/agents":
                    status,result=manage_agent(db,body)
                elif path.startswith("/api/agents/"):
                    status,result=manage_agent(db,body,path.removeprefix("/api/agents/"))
                elif path.startswith("/worker/"):
                    status,result=200,worker(db,path,body,executor)
                else:
                    raise Problem(404,"路径不存在")
                db.commit()
                if db.total_changes:stream_store.notify()
                return self.reply(status,result)
            match=stream_store.ROUTE.fullmatch(path)
            if match:return stream_store.serve(self,db,identifier(match[1]))
            db.execute("BEGIN")  # A snapshot and its revision must describe the same commit.
            if path=="/api/agents":
                return self.reply(200,agent_catalog(db))
            if path=="/api/executors":
                return self.reply(200,{"instance":station_instance(db),"executors":registry.catalog(db)})
            if path=="/api/state":
                rows=db.execute("""WITH ranked AS (
                  SELECT chat,status,created,substr(CASE WHEN answer<>'' THEN answer ELSE prompt END,1,100) AS preview,row_number() OVER(PARTITION BY chat ORDER BY created DESC) AS n,
                  first_value(prompt) OVER(PARTITION BY chat ORDER BY created) AS title FROM jobs)
                  SELECT r.chat,substr(r.title,1,52) AS title,r.status,r.created,r.preview,
                  e.id AS executor,e.name AS executor_name,
                  coalesce(a.agent,'codex') AS agent,coalesce(a.name,'Codex') AS agent_name,
                  EXISTS(SELECT 1 FROM native_unavailable WHERE thread=(
                    SELECT thread FROM native_links WHERE chat=r.chat ORDER BY rowid DESC LIMIT 1)) AS native_unavailable
                  FROM ranked r JOIN chat_executors x ON x.chat=r.chat JOIN executors e ON e.id=x.executor
                  LEFT JOIN chat_agents a ON a.chat=r.chat WHERE r.n=1 ORDER BY r.created DESC""")
                station=db.execute("SELECT last_seen FROM station WHERE id=1").fetchone()
                name=db.execute("SELECT value FROM station_config WHERE name='name'").fetchone()
                return self.reply(200,{"instance":station_instance(db), "chats_complete":True,"executors":registry.catalog(db),
                                      "deleted_chats":[r[0] for r in db.execute("SELECT chat FROM deleted_chats")],
                                      "station_name":name[0] if name else "Codex 工作站","chats":[dict(r) for r in rows],"online":bool(station and time.time()-station[0]<20)})
            if path.startswith("/api/chats/"):
                chat=identifier(path.removeprefix("/api/chats/"))
                items=[dict(r) for r in db.execute("SELECT id,prompt,filename,status,answer,created FROM jobs WHERE chat=? ORDER BY created",(chat,))]
                if not items:
                    raise unavailable_problem(db, chat, chat_availability(db, chat))
                items=[stream_store.turn_view(db,r) for r in items]
                revision=max((e["id"] for item in items for e in item["events"]),default=0)
                links=db.execute("SELECT thread FROM native_links WHERE chat=? ORDER BY rowid DESC LIMIT 1",(chat,)).fetchone()
                role = chat_agent(db,chat)
                return self.reply(200,{"turns":items,"revision":revision,"native_thread":links[0] if links else None,
                                       "agent":role["id"],"agent_name":role["name"],**registry.metadata(db,chat)})
            if path.startswith("/api/results/"):
                job=identifier(path.removeprefix("/api/results/"))
                row=db.execute("SELECT answer FROM jobs WHERE id=? AND status='done'",(job,)).fetchone()
                if row:
                    return self.reply(200,row[0].encode(),"text/plain; charset=utf-8",True)
            raise Problem(404,"路径不存在")

    def handle_request(self,method):
        try:
            self.route(method)
        except Problem as e:
            self.reply(e.status,{"error":str(e),**e.details})
        except (ValueError,UnicodeError):
            self.reply(400,{"error":"请求格式无效"})
        except (ConnectionError,TimeoutError):
            pass
        except sqlite3.Error:
            self.reply(503,{"error":"暂时无法访问数据，请用原请求重试"})

    def do_GET(self):
        self.handle_request("GET")

    def do_POST(self):
        self.handle_request("POST")


if __name__=="__main__":
    parser=argparse.ArgumentParser()
    parser.add_argument("--port",type=int,default=18742)
    parser.add_argument("--worker-port",type=int,help="Optional loopback listener restricted to worker credentials")
    args=parser.parse_args()
    service=ThreadingHTTPServer(("127.0.0.1",args.port),Handler)
    init()
    if args.worker_port is not None:
        from threading import Thread
        ingress=ThreadingHTTPServer(("127.0.0.1",args.worker_port),Handler)
        ingress.worker_only=True
        service.worker_port=ingress.server_port
        Thread(target=ingress.serve_forever,daemon=True).start()
    print(f"Whitecat http://127.0.0.1:{service.server_port}/",flush=True)
    service.serve_forever()
