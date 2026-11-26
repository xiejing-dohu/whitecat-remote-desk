"""Verify role management, immutable chats and the two Codex dispatch paths on isolated state."""
from contextlib import ExitStack, closing, contextmanager
from http.client import HTTPConnection
from http.server import ThreadingHTTPServer
from pathlib import Path
import hashlib
import json
import sqlite3
import tempfile
import threading
import uuid

import mobile_gateway
import server
import shared_cli
import stream_store
import worker


def uid():
    return str(uuid.uuid4())


@contextmanager
def serve(handler, **settings):
    with ThreadingHTTPServer(("127.0.0.1", 0), handler) as httpd:
        for name, value in settings.items():
            setattr(httpd, name, value)
        thread = threading.Thread(target=httpd.serve_forever, daemon=True)
        thread.start()
        try:
            yield httpd.server_port
        finally:
            httpd.shutdown()
            thread.join()


def request(port, path, body=None, token=None, expected=200):
    headers = {"Content-Type":"application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    payload = json.dumps(body, ensure_ascii=False).encode() if body is not None else None
    with closing(HTTPConnection("127.0.0.1", port, timeout=5)) as conn:
        conn.request("POST" if body is not None else "GET", path, payload, headers)
        reply = conn.getresponse()
        assert reply.status == expected, (path, reply.status, expected)
        return json.load(reply)


class QueueRecorder:
    def call(self, method, params):
        self.method, self.params = method, params
        return {"queuedSubmission":{"clientUserMessageId":params["clientUserMessageId"]}}


def verify_dispatch(job, instructions):
    resume = {"thread":uid()}
    for old in (None, resume):
        argv = worker.exec_args("codex", job, old)
        assert argv[argv.index("--sandbox")+1] == "read-only" and argv[-1] == "-"
        assert ("resume" in argv) == bool(old)
        if instructions:
            value = argv[argv.index("-c")+1]
            assert value.startswith("developer_instructions=")
            assert json.loads(value.partition("=")[2]) == instructions
        else:
            assert "-c" not in argv
    assert worker.thread_options(job) == ({"developerInstructions":instructions} if instructions else {})
    sender = shared_cli.Control("codex")
    sender.client = QueueRecorder()
    sender.enqueue(resume["thread"], worker.live_submission(job))
    params = sender.client.params
    assert sender.client.method == "thread/queue/add"
    assert params["threadId"] == resume["thread"] and params["clientUserMessageId"] == job["id"]
    text = params["input"][0]["text"]
    if instructions:
        assert instructions + "\n\n当前用户消息：\n" + job["prompt"] in text
        if job["filename"]:
            assert text.endswith(job["sample"])
    else:
        assert text == job["prompt"]


def main():
    with tempfile.TemporaryDirectory(prefix="whitecat-agents-") as tmp, ExitStack() as servers:
        server.STATE = mobile_gateway.STATE = Path(tmp)
        server.DB = server.STATE / "desk.sqlite"
        # Recreate an actual pre-agent database and command digest before migration.
        historic = {"command":uid(), "prompt":"已完成的旧会话"}
        old_chat, old_job = uid(), uid()
        with closing(sqlite3.connect(server.DB)) as db, db:
            db.execute("""CREATE TABLE jobs(
                id TEXT PRIMARY KEY, chat TEXT NOT NULL, command TEXT NOT NULL UNIQUE, digest TEXT NOT NULL,
                prompt TEXT NOT NULL, filename TEXT NOT NULL, sample TEXT NOT NULL, created REAL NOT NULL,
                status TEXT NOT NULL, ticket TEXT UNIQUE, answer TEXT NOT NULL DEFAULT '')""")
            raw = json.dumps([None,historic["prompt"],"",""], ensure_ascii=False).encode()
            db.execute("INSERT INTO jobs VALUES(?,?,?,?,?,'','',1,'done',NULL,'历史回复')",
                       (old_job,old_chat,historic["command"],hashlib.sha256(raw).hexdigest(),historic["prompt"]))
        key = server.init()
        core = servers.enter_context(serve(server.Handler, worker_key=key))
        gateway = servers.enter_context(serve(mobile_gateway.Mobile, upstream=core))
        request(gateway, "/api/agents", expected=401)
        request(gateway, "/api/agents", {}, expected=401)
        token = request(gateway, "/pair", {"code":mobile_gateway.pairing_code()})["token"]

        def api(path, body=None, status=200):
            return request(gateway, path, body, token, status)

        def station(action, body):
            return request(core, "/worker/" + action, body, key)

        def send(body, status=201):
            return api("/api/jobs", body, status)

        replay = send(historic, 200)
        assert replay == {"job":old_job,"chat":old_chat,"agent":"codex","agent_name":"Codex"}
        assert api("/api/chats/" + old_chat)["turns"][0]["answer"] == "历史回复"
        assert api("/api/agents")["agents"] == [server.DEFAULT_AGENT]
        aid = uid()
        path = "/api/agents/" + aid
        role = {"id":aid,"name":"数据助手","instructions":'第一行 "引号" \u2606\n核对数据与附件。'}
        api("/api/agents", role, 201)
        assert api("/api/agents", role)["agent"]["id"] == aid
        api("/api/agents", dict(role, instructions="不同配置"), 409)
        api("/api/agents", dict(role, id=uid(), name=" "), 400)
        api(path, {"archived":1}, 400)

        original = {"command":uid(),"prompt":"核对文本","agent":aid,"filename":"note.txt","sample":"42"}
        first = send(original)
        assert send(original, 200) == first
        send(dict(original, agent="codex"), 409)
        assert first["agent_name"] == role["name"]
        renamed = {"name":"复核助手","instructions":"使用更新后的说明"}
        api(path, renamed)
        second = send({"command":uid(),"agent":aid,"prompt":"新会话"})
        assert second["agent_name"] == renamed["name"]

        legacy_body = {"command":uid(),"prompt":"旧版客户端"}
        legacy = send(legacy_body)
        assert send(dict(legacy_body, agent="codex"), 200) == legacy
        legacy_job = station("claim", {"ticket":uid()})
        assert legacy_job["id"] == legacy["job"]
        verify_dispatch(legacy_job, "")

        ticket = uid()
        capable = {"ticket":ticket,"agent_roles":True}
        job = station("claim", capable)
        assert job == station("claim", capable)
        assert job["id"] == first["job"] and job["agent"]["instructions"] == role["instructions"]
        assert station("claim", {"ticket":ticket}) == {}
        verify_dispatch(job, role["instructions"])
        station("finish", {"ticket":ticket,"job":job["id"],"status":"done","answer":"已核对"})

        assert api(path, {"archived":True})["agent"]["archived"]
        assert api(path, {"archived":True})["agent"]["archived"]
        api(path, renamed, 409)
        send({"command":uid(),"prompt":"停用角色","agent":aid}, 409)
        send({"command":uid(),"prompt":"未知角色","agent":uid()}, 404)
        assert send(original, 200) == first
        assert api("/api/agents")["agents"][1]["archived"]
        follow = {"chat":first["chat"],"command":uid(),"prompt":"继续"}
        receipt = send(follow)
        assert receipt["agent_name"] == role["name"]
        assert send(dict(follow, agent=aid), 200) == receipt
        send(dict(follow, command=uid(), agent="codex"), 409)
        second_job = station("claim", {"ticket":uid(),"agent_roles":True})
        assert second_job["id"] == second["job"]
        verify_dispatch(second_job, renamed["instructions"])
        follow_ticket = uid()
        follow_job = station("claim", {"ticket":follow_ticket,"agent_roles":True})
        assert follow_job["parent"] == first["job"] and follow_job["agent"] == job["agent"]
        assert station("history", {"ticket":follow_ticket,"job":follow_job["id"]})["turns"][0]["prompt"] == original["prompt"]
        state = {item["chat"]:item for item in api("/api/state")["chats"]}
        assert state[first["chat"]]["agent_name"] == role["name"]
        assert state[second["chat"]]["agent_name"] == renamed["name"]
        assert state[legacy["chat"]]["agent"] == "codex"
        doc = api("/api/chats/" + first["chat"])
        assert doc["agent_name"] == role["name"] and len(doc["turns"]) == 2
        with closing(sqlite3.connect(server.DB)) as db:
            db.row_factory = sqlite3.Row
            assert stream_store.frame(db, first["chat"], None)[1]["agent_name"] == role["name"]
        server.init()
        assert api("/api/chats/" + second["chat"])["agent_name"] == renamed["name"]
    print("PASS paired management, two role configurations, archive, immutable chat snapshots, legacy CLI,")
    print("     idempotent commands and claims, SSE metadata, CLI new/resume and native queue payloads")


if __name__ == "__main__":
    main()
