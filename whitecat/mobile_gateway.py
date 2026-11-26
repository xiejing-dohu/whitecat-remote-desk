"""Authenticated Android entry point for the existing Whitecat API."""
import argparse
import hashlib
import http.client
import json
import re
import secrets
import sqlite3
import ssl
import time
from contextlib import closing
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import migration_relay
from server import STATE
from stream_store import ROUTE

PAIR_TTL = 600
READ = re.compile(r"/api/(?:state|chats/[a-f0-9-]{36}|results/[a-f0-9-]{36})\Z")


def digest(text):
    return hashlib.sha256(text.encode()).hexdigest()


def connect():
    STATE.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(STATE / "mobile.sqlite", timeout=12)
    db.execute("CREATE TABLE IF NOT EXISTS codes(hash TEXT PRIMARY KEY, expires REAL NOT NULL)")
    db.execute("CREATE TABLE IF NOT EXISTS devices(hash TEXT PRIMARY KEY, paired REAL NOT NULL)")
    migration_relay.setup(db)
    return db


def pairing_code():
    code = secrets.token_hex(6).upper()
    with closing(connect()) as db, db:
        db.execute("DELETE FROM codes WHERE expires<=?", (time.time(),))
        db.execute("INSERT INTO codes VALUES(?,?)", (digest(code), time.time()+PAIR_TTL))
    return code


def exchange(code):
    token = secrets.token_urlsafe(32)
    with closing(connect()) as db, db:
        used = db.execute("DELETE FROM codes WHERE hash=? AND expires>?", (digest(code.upper()), time.time())).rowcount
        if used != 1:
            return None
        db.execute("INSERT INTO devices VALUES(?,?)", (digest(token), time.time()))
    return token


class Mobile(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, status, value, mime="application/json; charset=utf-8"):
        data=value if isinstance(value,bytes) else json.dumps(value,ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type",mime)
        self.send_header("Content-Length",str(len(data)))
        self.send_header("Cache-Control","no-store")
        self.end_headers()
        self.wfile.write(data)

    def dispatch(self, method):
        if self.headers.get("Origin"):
            return self.reply(403,{"error":"请使用白猫 App"})
        pair = method == "POST" and self.path == "/pair"
        transfer = method == "POST" and self.path in migration_relay.ROUTES
        proxy_get = method == "GET" and (READ.fullmatch(self.path) or ROUTE.fullmatch(self.path) or self.path in {"/api/agents","/api/executors"})
        proxy_post = method == "POST" and (self.path in {"/api/jobs", "/api/agents"} or
            re.fullmatch(r"/api/agents/[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}", self.path))
        if not (pair or transfer or proxy_get or proxy_post):
            return self.reply(404, {"error":"路径不存在"})
        device = None
        if not pair:
            auth = self.headers.get("Authorization", "")
            if auth.startswith("Bearer "):
                device = digest(auth[7:])
                with closing(connect()) as db:
                    if not db.execute("SELECT 1 FROM devices WHERE hash=?", (device,)).fetchone():
                        device = None
            if device is None:
                return self.reply(401, {"error":"请重新配对工作站"})
        data = None
        if method == "POST":
            if self.headers.get_content_type() != "application/json":
                return self.reply(415, {"error":"需要 JSON"})
            limit = migration_relay.MAX_REQUEST if self.path == "/api/migration/create" else 550000
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= limit:
                return self.reply(413, {"error":"请求过大"})
            self.connection.settimeout(15)
            data = self.rfile.read(length)
            if len(data) != length:
                return self.reply(400, {"error":"请求不完整"})
        if pair:
            body=json.loads(data)
            code=body.get("code") if isinstance(body,dict) else None
            token=exchange(code) if isinstance(code,str) and re.fullmatch("[a-fA-F0-9]{12}",code) else None
            return self.reply(200,{"token":token}) if token else self.reply(403,{"error":"配对码无效或已过期"})
        if transfer:
            with closing(connect()) as db:
                status, answer = migration_relay.handle(db, self.path, json.loads(data), device)
            return self.reply(status, answer)
        with closing(http.client.HTTPConnection("127.0.0.1",self.server.upstream,timeout=20)) as conn:
            headers={"X-Whitecat":"desk","Content-Type":"application/json"}
            if ROUTE.fullmatch(self.path) and self.headers.get("Last-Event-ID"):headers["Last-Event-ID"]=self.headers["Last-Event-ID"]
            conn.request(method,self.path,data,headers)
            response=conn.getresponse()
            if ROUTE.fullmatch(self.path) and response.status==200:
                self.send_response(200);self.send_header("Content-Type","text/event-stream; charset=utf-8")
                self.send_header("Cache-Control","no-store");self.end_headers()
                self.connection.settimeout(12)
                try:
                    while line:=response.readline():
                        self.wfile.write(line)
                        if line==b"\n":self.wfile.flush()
                except (OSError,http.client.HTTPException):pass
                return
            return self.reply(response.status,response.read(),response.getheader("Content-Type") or "application/json")

    def handle_api(self,method):
        try:
            self.dispatch(method)
        except (ValueError,UnicodeError):
            self.reply(400,{"error":"请求格式有误"})
        except (OSError,http.client.HTTPException,sqlite3.Error):
            try:
                self.reply(503,{"error":"工作台暂时无法连接，请保留原命令重试"})
            except OSError:
                pass

    def do_GET(self):
        self.handle_api("GET")

    def do_POST(self):
        self.handle_api("POST")


class MobileServer(ThreadingHTTPServer):
    next_prune = 0

    def service_actions(self):
        if time.monotonic() >= self.next_prune:
            self.next_prune = time.monotonic() + 60
            try:
                with closing(connect()) as db, db:
                    migration_relay.prune(db, time.time())
            except sqlite3.Error:
                pass  # Requests independently enforce expiry while the database is busy.


if __name__ == "__main__":
    parser=argparse.ArgumentParser()
    parser.add_argument("action",choices=["pair","serve"])
    parser.add_argument("--port",type=int,default=18744)
    parser.add_argument("--upstream",type=int,default=18742)
    parser.add_argument("--bind",default="127.0.0.1")
    parser.add_argument("--cert")
    parser.add_argument("--key")
    args=parser.parse_args()
    if args.action=="pair":
        print(pairing_code())
    else:
        if bool(args.cert)!=bool(args.key) or (args.bind!="127.0.0.1" and not args.cert):
            parser.error("External listeners require --cert and --key")
        server=MobileServer((args.bind,args.port),Mobile)
        server.upstream=args.upstream
        if args.cert:
            context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            context.minimum_version=ssl.TLSVersion.TLSv1_2
            context.load_cert_chain(args.cert,args.key)
            server.socket=context.wrap_socket(server.socket,server_side=True)
        server.serve_forever()
