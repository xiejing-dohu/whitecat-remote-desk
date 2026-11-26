"""Exercise phone receipts, live terminal steering and replay against a private fresh database."""
import hashlib
import io
import json
import sqlite3
from contextlib import closing
import tempfile
import uuid
from pathlib import Path

import native_sync
import server


def main():
    with tempfile.TemporaryDirectory() as directory:
        server.STATE=Path(directory);server.DB=server.STATE/"desk.sqlite";server.init()
        with closing(sqlite3.connect(server.DB)) as db, db:
            db.row_factory=sqlite3.Row
            thread,chat,ticket=[str(uuid.uuid4()) for _ in range(3)]
            start={"thread":thread,"chat":chat,"turn":str(uuid.uuid4()),"at":1,"prompt":"base","answer":"41","status":"done"}
            server.import_native(db,start)
            message={"chat":chat,"command":str(uuid.uuid4()),"prompt":"phone"}
            code,receipt=server.submit(db,message);assert code==201
            job=server.claim(db,ticket);assert job["id"]==receipt["job"]
            server.worker(db,"/worker/live-intent",dict(job=job["id"],ticket=ticket,thread=thread))
            turn=str(uuid.uuid4())
            def line(kind,**data):
                row={"type":"event_msg","timestamp":"2026-11-26T04:00:00Z","payload":dict(type=kind,turn_id=turn,**data)}
                return json.dumps(row).encode()+b"\n"
            def user(text,client):
                return line("item_completed",item={"type":"UserMessage","id":str(uuid.uuid4()),"client_id":client,
                                                   "content":[{"type":"Text","text":text}]})
            raw=line("task_started")+user("phone",job["id"])+user("terminal steer",str(uuid.uuid4()))
            def replay(data):
                for kind,record,_,_ in native_sync.records(io.BytesIO(data),thread,hashlib.sha256(),True):
                    if kind!="end":assert server.import_native(db,dict(record,thread=thread,chat=chat))["job"]==job["id"]
            replay(raw);replay(raw)
            assert db.execute("SELECT status FROM jobs WHERE id=?",(job["id"],)).fetchone()[0]=="running"
            raw+=line("task_complete",last_agent_message="joint result")
            replay(raw);replay(raw)
            assert tuple(db.execute("SELECT prompt,answer,status FROM jobs WHERE id=?",(job["id"],)).fetchone())==("phone","joint result","done")
            assert [r[0] for r in db.execute("SELECT prompt FROM native_followups")]==["terminal steer"]
            assert server.submit(db,message)==(200,receipt)
            assert db.execute("SELECT count(*) FROM jobs").fetchone()[0]==2
    print("PASS immutable phone command, additional terminal input during one turn, repeated delivery and final reply")


if __name__=="__main__":main()
