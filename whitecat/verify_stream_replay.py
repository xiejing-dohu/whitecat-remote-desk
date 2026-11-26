"""Check SSE cursor replay, native item identity and transaction rollback on a fresh database."""
import sqlite3
import tempfile
from contextlib import closing
from pathlib import Path
from uuid import uuid4
import server
import stream_store

def main():
    with tempfile.TemporaryDirectory() as tmp:
        server.STATE=Path(tmp);server.DB=server.STATE/"test.sqlite";server.init()
        with closing(sqlite3.connect(server.DB)) as db:
            db.row_factory=sqlite3.Row
            chat,thread,turn=[str(uuid4()) for _ in range(3)]
            rec={"chat":chat,"thread":thread,"turn":turn,"at":1,"status":"running","prompt":"demo","answer":""}
            job=server.import_native(db,rec)["job"];db.commit()
            def frame(cursor):return stream_store.frame(db,chat,cursor)
            def push(key,kind,text,state,progress=0):
                stream_store.store(db,{"thread":thread,"parts":[dict(turn=turn,item=key,kind=kind,body=text,status=state,progress=progress)]})
            kind,snapshot,start=frame(None);assert kind=="snapshot" and len(snapshot["turns"])==1
            push("tool","mcp","","running",1);push("txt","answer","白","running");db.commit()
            k,d,offline=frame(start);assert k=="patch" and len(d["parts"])==2
            push("txt","answer","白猫","running");push("tool","mcp","","done",3);db.commit()
            k,d,resumed=frame(offline);assert k=="patch" and resumed>offline
            assert {p["item"]:p["body"] for p in d["parts"]}=={"tool":"","txt":"白猫"}
            push("txt","answer","白猫","running");push("tool","mcp","","running",1);db.commit()
            assert frame(resumed)[0]=="presence"
            push("txt","answer","白猫完成","done");server.import_native(db,dict(rec,status="done",answer="白猫完成"));db.commit()
            k,d,end=frame(resumed);assert end>resumed and d["turns"][0]["status"]=="done"
            assert d["turns"][0]["answer"]=="白猫完成" and len(d["turns"][0]["parts"])==2
            push("txt","answer","白猫完成","done");db.rollback()
            assert frame(end)[0]=="presence" and frame(end+100)[0]=="snapshot"
            assert db.execute("SELECT COUNT(*) FROM jobs WHERE id=?",(job,)).fetchone()[0]==1
    print("PASS snapshot, unicode prefix replacement, missed tool transitions, replay, committed cursor and final reconciliation")

if __name__=="__main__":main()
