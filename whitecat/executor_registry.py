"""Execution identities for one Whitecat service. Chat roles are managed separately."""
import hashlib
import re
import time

DEFAULT="default"
ID=re.compile(r"[a-z][a-z0-9_-]{0,47}\Z")
ONLINE_SECONDS=20


def key_hash(secret):
    return hashlib.sha256(secret.encode("utf-8")).hexdigest()


def initialize(db):
    db.executescript("""
    CREATE TABLE IF NOT EXISTS executors(
        id TEXT PRIMARY KEY, name TEXT NOT NULL, key_hash TEXT UNIQUE, last_seen REAL NOT NULL DEFAULT 0);
    CREATE TABLE IF NOT EXISTS chat_executors(chat TEXT PRIMARY KEY, executor TEXT NOT NULL);
    CREATE INDEX IF NOT EXISTS chats_by_executor ON chat_executors(executor,chat);
    INSERT OR IGNORE INTO executors(id,name) VALUES('default','默认 Agent');
    INSERT OR IGNORE INTO chat_executors SELECT DISTINCT chat,'default' FROM jobs;
    INSERT OR IGNORE INTO chat_executors SELECT chat,'default' FROM deleted_chats;
    CREATE TRIGGER IF NOT EXISTS default_chat_executor AFTER INSERT ON jobs
      WHEN NOT EXISTS(SELECT 1 FROM chat_executors WHERE chat=new.chat)
      BEGIN INSERT INTO chat_executors(chat,executor) VALUES(new.chat,'default'); END;
    """)


def authenticate(db, authorization):
    if not authorization.startswith("Bearer "):return None
    secret=authorization[7:]
    if not secret or len(secret)>256:return None
    row=db.execute("SELECT id FROM executors WHERE key_hash=?", (key_hash(secret),)).fetchone()
    return row[0] if row else None


def catalog(db):
    now=time.time()
    return [{"id":identity,"name":name,"online":now-seen<ONLINE_SECONDS,"last_seen":seen or None}
            for identity,name,seen in db.execute("SELECT id,name,last_seen FROM executors ORDER BY id<>'default',name,id")]


def owner(db,chat):
    row=db.execute("SELECT executor FROM chat_executors WHERE chat=?",(chat,)).fetchone()
    return row[0] if row else DEFAULT


def receipt_owner(db,executor):
    if executor==DEFAULT:return {}
    return {"executor":executor,"executor_name":db.execute("SELECT name FROM executors WHERE id=?",(executor,)).fetchone()[0]}


def metadata(db,chat):
    row=db.execute("""SELECT e.id,e.name,e.last_seen FROM executors e WHERE e.id=
        coalesce((SELECT executor FROM chat_executors WHERE chat=?),'default')""",(chat,)).fetchone()
    return {"executor":row[0],"executor_name":row[1],"executor_online":time.time()-row[2]<ONLINE_SECONDS}


class EnrollmentError(ValueError):
    def __init__(self,status,message):
        super().__init__(message)
        self.status=status


def private_directory(path):
    import os
    path.mkdir(mode=0o700)
    if os.name=="nt":
        # The Windows mode argument cannot guarantee an owner-only ACL on older runtimes.
        import csv
        import subprocess
        try:
            who=subprocess.run(["whoami","/user","/fo","csv","/nh"],capture_output=True,check=True)
            sid=next(csv.reader([who.stdout.decode("mbcs",errors="replace").strip()]))[-1]
            if not re.fullmatch(r"S-[0-9-]+",sid):raise ValueError("无效账号 SID")
            subprocess.run(["icacls",str(path),"/inheritance:r","/grant:r",f"*{sid}:(OI)(CI)F"],
                           check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        except (OSError,subprocess.SubprocessError,ValueError,IndexError,StopIteration) as err:
            path.rmdir()
            raise OSError("无法创建仅允许工作站账号访问的私有目录") from err


def enroll(db,identity,name,directory,endpoint):
    import json
    import os
    import secrets
    from urllib.parse import urlsplit
    if not isinstance(identity,str) or not ID.fullmatch(identity) or identity==DEFAULT:
        raise EnrollmentError(400,"执行 Agent ID 应为小写字母开头的 1 至 48 位字母、数字、下划线或连字符，并避开 default")
    if not isinstance(name,str) or not 1<=len(name.strip())<=72 or any(ord(c)<32 for c in name):
        raise EnrollmentError(400,"执行 Agent 名称无效")
    parsed=urlsplit(endpoint)
    if parsed.scheme!="http" or parsed.hostname not in ("localhost","127.0.0.1") or parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path not in ("","/"):
        raise EnrollmentError(400,"工作站地址须指向本机服务或 SSH 转发端口")
    if not db.in_transaction:db.execute("BEGIN IMMEDIATE")
    if db.execute("SELECT 1 FROM executors WHERE id=?",(identity,)).fetchone():
        raise EnrollmentError(409,"执行 Agent ID 已存在")
    directory.parent.mkdir(mode=0o700,parents=True,exist_ok=True)
    try:private_directory(directory)
    except FileExistsError:raise EnrollmentError(409,"配置目录已存在，请使用新的目录") from None
    keyfile=directory/"worker.key"
    config=directory/"station.json"
    secret=secrets.token_urlsafe(48)
    settings={"server":endpoint.rstrip("/"),"executor":identity,"name":name.strip(),"key_file":"worker.key","spool":"spool"}
    written=[]
    try:
        for path,data in ((keyfile,secret+"\n"),(config,json.dumps(settings,ensure_ascii=False,indent=2)+"\n")):
            fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
            written.append(path)
            with os.fdopen(fd,"wb") as output:
                output.write(data.encode("utf8"));output.flush();os.fsync(output.fileno())
        db.execute("INSERT INTO executors(id,name,key_hash) VALUES(?,?,?)",(identity,name.strip(),key_hash(secret)))
        db.commit()
    except Exception:
        db.rollback()
        for path in written:path.unlink(missing_ok=True)
        directory.rmdir()
        raise
    return {"executor":{"id":identity,"name":name.strip()},"config_path":str(config.resolve())}
