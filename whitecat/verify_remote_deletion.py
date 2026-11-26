"""Use only synthetic records inside a temporary Whitecat and Codex home."""
import json
import os
from http.client import HTTPConnection
import subprocess
import sys
import sqlite3
import tempfile
from contextlib import closing
from pathlib import Path
from unittest.mock import patch
from uuid import uuid4

import native_sync
import server
import stream_store


def uid():
    return str(uuid4())


def verify_http(chat,instance,root):
    # Run the same script entry point as a deployment, with a private temporary state.
    cmd=[sys.executable,"-B",str(Path(server.__file__).resolve()),"--port","0"]
    private_state=root/"http"
    env=dict(os.environ,WHITECAT_STATE=str(private_state),PYTHONIOENCODING="utf-8")
    proc=subprocess.Popen(cmd,env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,encoding="utf-8")
    try:
        address=proc.stdout.readline().strip()
        port=int(address.rstrip("/").rsplit(":",1)[1])
        with closing(sqlite3.connect(private_state/"desk.sqlite")) as db:
            db.row_factory=sqlite3.Row
            native=dict(chat=chat,thread=uid(),turn=uid(),at=1,status="done",prompt="fixture",answer="ok")
            server.import_native(db,native)
            db.execute("DELETE FROM jobs WHERE chat=?",(chat,))
            db.commit()
            serving_instance=server.station_instance(db)
            assert instance!=serving_instance
        for path,expected in [("/api/state",200),("/api/chats/"+chat+"/stream",404),("/api/chats/"+chat,404)]:
            with closing(HTTPConnection("127.0.0.1",port,timeout=5)) as conn:
                conn.request("GET",path,headers={"X-Whitecat":"desk"})
                res=conn.getresponse();body=json.load(res)
                assert res.status==expected and body["instance"]==serving_instance
                if expected==404:assert body["chat"]==chat and body["code"]=="chat_deleted"
                else:assert body["chats_complete"] and chat in body["deleted_chats"] and not body["chats"]
    finally:
        proc.terminate();proc.wait(timeout=5)
        proc.stdout.close();proc.stderr.close()


def verify():
    with tempfile.TemporaryDirectory() as tmp:
        root=Path(tmp)
        server.STATE=root/"whitecat"
        server.DB=server.STATE/"fixture.sqlite"
        server.init()
        thread,chat,turn=uid(),uid(),uid()
        with closing(sqlite3.connect(server.DB)) as db:
            db.row_factory=sqlite3.Row
            instance=server.station_instance(db)
            native={"chat":chat,"thread":thread,"turn":turn,"at":1,"status":"done","prompt":"sample","answer":"ok"}
            server.import_native(db,native)
            db.commit()
            try:server.submit(db,dict(command=uid(),chat=chat,prompt="old request",expected_instance=uid()))
            except server.Problem as err:
                assert err.status==409 and err.details==dict(code="station_changed",chat=chat,instance=instance)
            else:raise AssertionError("Accepted a request addressed to another station")
            _,_,seq=stream_store.frame(db,chat,None)
            def post(action,body):
                return server.worker(db,"/worker/"+action,body)
            sync=native_sync.NativeSync(root/"spool",post)
            sync.state=post("native-state",{})
            binding=dict(chat=chat,thread=thread,head=turn)
            home=root/"codex"
            path=home/"sessions"/"2026"/"01"/"01"/("rollout-day-"+thread+".jsonl")
            path.parent.mkdir(parents=True)
            path.write_bytes(b"fixture")
            archive=home/"archived_sessions"
            archive.mkdir()
            index=home/"state_5.sqlite"
            with closing(sqlite3.connect(index)) as codex, patch.dict(os.environ,{"CODEX_HOME":str(home)}):
                codex.execute("CREATE TABLE threads(id TEXT PRIMARY KEY, archived INTEGER, rollout_path TEXT)")
                codex.execute("INSERT INTO threads VALUES(?,0,?)",(thread,str(path)))
                codex.commit()
                def probe(stamp):sync.check_binding(binding,path,stamp)
                def assert_available():
                    assert server.chat_availability(db,chat)==""
                    assert stream_store.frame(db,chat,seq)[1]["native_unavailable"] is False

                probe(0)
                zipped=path.with_suffix(".jsonl.zst")
                path.rename(zipped)
                probe(1);assert_available()
                archived=archive/zipped.name
                zipped.rename(archived)
                codex.execute("UPDATE threads SET archived=1,rollout_path=? WHERE id=?",(str(archived),thread))
                codex.commit();probe(2);probe(15);assert_available()
                codex.execute("DELETE FROM threads WHERE id=?",(thread,));codex.commit()
                probe(20);probe(30);assert_available()
                archived.unlink()  # The only file removed is the synthetic rollout above.
                probe(32);assert_available()
                probe(42)
                assert server.chat_availability(db,chat)=="native_unavailable"
                assert stream_store.frame(db,chat,seq)[1]["native_unavailable"] is True
                try:server.submit(db,dict(command=uid(),chat=chat,prompt="blocked"))
                except server.Problem as err:
                    assert err.status==409 and err.details==dict(instance=instance,chat=chat,code="native_unavailable")
                else:raise AssertionError("Submitted to an unavailable native history")
                path.write_bytes(b"restored fixture")
                probe(48);assert_available()
                path.unlink()
                probe(50)
                with patch.dict(os.environ,{"CODEX_HOME":str(root/"different-installation")}):
                    probe(70);assert_available()
                probe(71);assert_available()
                sync.live={thread}
                probe(89);assert_available()

            second=server.import_native(db,dict(native,turn=uid(),at=2))["job"]
            db.execute("DELETE FROM jobs WHERE id=?",(second,))
            assert server.chat_availability(db,chat)==""
            db.execute("DELETE FROM jobs WHERE chat=?",(chat,))
            kind,payload,_=stream_store.frame(db,chat,seq)
            assert kind=="unavailable" and payload["code"]=="chat_deleted"
            assert payload["instance"]==instance and payload["chat"]==chat
            assert server.chat_availability(db,uid())=="chat_missing"
            for action,body in [("native-import",native),("native-bind",dict(chat=chat,thread=thread))]:
                try:post(action,body)
                except server.Problem as err:
                    assert err.status==404 and err.details["code"]=="chat_deleted"
                else:raise AssertionError("Deleted chat replayed")
            # A surviving marker and an active native index entry must also be ignored after restarting the worker.
            fresh=native_sync.NativeSync(root/"spool",post)
            folder=fresh.spool/"chats"/instance/chat;folder.mkdir(parents=True)
            (folder/"binding.json").write_text(json.dumps(dict(binding,path=str(path))),encoding="utf-8")
            with patch.object(native_sync,"cli_index",return_value=[(thread,path,str(root))]):
                assert fresh.poll()==[] and not fresh.registered
            assert not db.execute("SELECT 1 FROM jobs WHERE chat=?",(chat,)).fetchone()
            # Keep transaction atomic when maintenance or a restore rolls back.
            db.rollback()
            assert server.chat_availability(db,chat)==""
            saved_job=dict(db.execute("SELECT * FROM jobs WHERE chat=?",(chat,)).fetchone())
            db.execute("DELETE FROM jobs WHERE chat=?",(chat,));db.commit()
            db.execute("INSERT INTO jobs("+",".join(saved_job)+") VALUES("+",".join("?" for _ in saved_job)+")",tuple(saved_job.values()))
            db.commit()
            assert server.import_native(db,native)["job"]==saved_job["id"]
            assert server.chat_availability(db,chat)=="" and db.execute("SELECT count(*) FROM jobs WHERE chat=?",(chat,)).fetchone()[0]==1
        verify_http(chat,instance,root)
    print("PASS synthetic Whitecat deletion, SSE heartbeat, native unavailable/restore, archive, compression, instance and rollback")


if __name__=="__main__":
    verify()
