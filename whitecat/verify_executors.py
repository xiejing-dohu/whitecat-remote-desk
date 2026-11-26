"""Exercise two credentialed executor identities and their queues against private HTTP and SQLite fixtures."""
from contextlib import ExitStack, closing
from http.client import HTTPConnection
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
from uuid import uuid4

import executor_registry
import mobile_gateway
import native_sync
import server
from verify_agents import serve

ROOT=Path(__file__).resolve().parent


def uid():
    return str(uuid4())


def request(port,path,body=None,key=None,expected=200,operator=False):
    headers={"Content-Type":"application/json"}
    if key:headers["Authorization"]="Bearer "+key
    if operator:headers["X-Whitecat"]="desk"
    data=json.dumps(body,ensure_ascii=False).encode() if body is not None else None
    with closing(HTTPConnection("127.0.0.1",port,timeout=6)) as client:
        client.request("POST" if data is not None else "GET",path,data,headers)
        response=client.getresponse();payload=json.load(response)
        assert response.status==expected,(path,response.status,expected,payload.get("error"))
        return payload


def run():
    with tempfile.TemporaryDirectory() as tmp,ExitStack() as stack:
        root=Path(tmp)
        server.STATE=mobile_gateway.STATE=root/"server";server.DB=server.STATE/"desk.sqlite"
        default=server.init()
        core=stack.enter_context(serve(server.Handler))
        worker_port=stack.enter_context(serve(server.Handler,worker_only=True))
        mobile=stack.enter_context(serve(mobile_gateway.Mobile,upstream=core))
        state=lambda:request(core,"/api/state",operator=True)
        enroll=lambda body,status:request(core,"/api/admin/executors",body,expected=status,operator=True)
        request(mobile,"/api/executors",expected=401)
        device=request(mobile,"/pair",{"code":mobile_gateway.pairing_code()})["token"]
        result=enroll({"id":"review","name":"复核 Agent"},201)
        config=Path(result["config_path"]);conf=json.loads(config.read_text("utf8"))
        keyfile=config.parent/conf["key_file"];review=keyfile.read_text().strip()
        assert default!=review and len(review)>=50 and review not in json.dumps(result) and review not in config.read_text("utf8")
        if os.name!="nt":assert (keyfile.stat().st_mode & 0o777)==0o600 and (config.parent.stat().st_mode & 0o777)==0o700
        enroll({"id":"review","name":"重复"},409)
        enroll({"id":"../escape","name":"无效"},400)
        enroll({"id":"default","name":"保留"},400)
        request(mobile,"/api/admin/executors",{"id":"bad","name":"bad"},device,404)
        request(worker_port,"/api/admin/executors",{"id":"bad","name":"bad"},review,403,True)
        request(worker_port,"/api/state",key=review,expected=403,operator=True)
        request(worker_port,"/worker/claim",{"ticket":uid()},key="invalid",expected=403)
        env=dict(os.environ,WHITECAT_STATE=str(server.STATE),PYTHONIOENCODING="utf-8")
        check=subprocess.run([sys.executable,"-B",str(ROOT/"executor_admin.py"),"add","--id","offline","--name","离线 Agent",
                              "--config-dir",str(root/"offline station"),"--server",f"http://127.0.0.1:{worker_port}"],
                              env=env,capture_output=True,check=True)
        assert json.loads(check.stdout)["executor"]["id"]=="offline"
        assert not check.stderr and review.encode() not in check.stdout
        def worker(key,action,body,status=200):return request(worker_port,"/worker/"+action,body,key,status)
        def send(body,status=201):return request(mobile,"/api/jobs",body,device,status)
        assert worker(default,"heartbeat",{"name":"隔离服务器"})["executor"]=="default"
        assert worker(review,"heartbeat",{"name":"禁止改服务器名","executor":"default"})["executor"]=="review"
        assert state()["station_name"]=="隔离服务器"
        roster={r["id"]:r for r in request(mobile,"/api/executors",key=device)["executors"]}
        assert roster["default"]["online"] and roster["review"]["online"] and not roster["offline"]["online"]
        offline=send({"executor":"offline","command":uid(),"prompt":"留在离线队列"})
        first={"executor":"review","command":uid(),"prompt":"复核 A"}
        a=send(first)
        b=send({"executor":"review","command":uid(),"prompt":"复核 B"})
        assert a["chat"]!=b["chat"]
        assert send(first,200)==a
        send(dict(first,executor="default"),409)
        send({"chat":a["chat"],"command":uid(),"prompt":"换人","executor":"default"},409)
        send({"executor":"absent","command":uid(),"prompt":"未知"},404)
        original={"command":uid(),"prompt":"默认 C"}
        c=send(original);assert send(dict(original,executor="default"),200)==c
        d_ticket,r_ticket=uid(),uid()
        d=worker(default,"claim",{"ticket":d_ticket,"executor":"review"})
        r=worker(review,"claim",{"ticket":r_ticket,"executor":"default"})
        assert (d["id"],d["executor"],r["id"],r["executor"])==(c["job"],"default",a["job"],"review")
        worker(review,"claim",{"ticket":d_ticket},403)
        assert worker(default,"claim",{"ticket":uid()})=={}
        for action in ("event","finish","history","defer","live-intent"):
            worker(default,action,{"ticket":r_ticket,"job":a["job"]},403)
            worker(review,action,{"ticket":d_ticket,"job":c["job"]},403)
        ta,tc=uid(),uid()
        worker(review,"native-bind",{"chat":a["chat"],"thread":ta})
        worker(default,"native-bind",{"chat":c["chat"],"thread":tc})
        for key,chat,thread in ((default,a["chat"],ta),(review,c["chat"],tc)):
            worker(key,"native-bind",{"chat":chat,"thread":uid()},403)
            worker(key,"native-import",{"chat":chat,"thread":thread},403)
            worker(key,"stream",{"thread":thread,"parts":[]},403)
            worker(key,"native-availability",{"chat":chat,"thread":thread},403)
        worker(review,"native-import",{"thread":uid(),"chat":uid()},403)
        worker(review,"stream",{"thread":ta,"parts":[]})
        s1=worker(default,"native-state",{});s2=worker(review,"native-state",{})
        assert s1["links"]=={tc:c["chat"]} and s2["links"]=={ta:a["chat"]}
        assert s1["discover_unbound"] and not s2["discover_unbound"]
        from unittest.mock import patch
        with patch.object(native_sync,"cli_index",side_effect=AssertionError("Read unrelated CLI index")):
            assert native_sync.NativeSync(root/"new-spool",lambda a,b:s2).poll()==[]
        worker(default,"finish",dict(ticket=d_ticket,job=c["job"],status="done",answer="C"))
        worker(review,"finish",dict(ticket=r_ticket,job=a["job"],status="done",answer="A"))
        nxt=uid();r2=worker(review,"claim",{"ticket":nxt})
        assert r2["id"]==b["job"] and worker(default,"claim",{"ticket":uid()})=={}
        worker(review,"finish",dict(ticket=nxt,job=b["job"],status="done",answer="B"))
        follow=send({"chat":a["chat"],"command":uid(),"prompt":"续聊"})
        ticket=uid();job=worker(review,"claim",{"ticket":ticket})
        assert job["id"]==follow["job"] and job["history"][0]["answer"]=="A" and job["agent"]["id"]=="codex"
        worker(review,"finish",dict(ticket=ticket,job=job["id"],status="done",answer="A2"))
        rows=state()["chats"]
        assert {i["chat"] for i in rows if i["executor"]=="review"}=={a["chat"],b["chat"]}
        assert next(i for i in rows if i["chat"]==offline["chat"])["status"]=="queued"
        doc=request(mobile,"/api/chats/"+a["chat"],key=device)
        assert doc["executor"]=="review" and doc["executor_name"]=="复核 Agent" and len(doc["turns"])==2
        with closing(sqlite3.connect(server.DB)) as db:
            assert not any(review in line or default in line for line in db.iterdump())
            assert db.execute("SELECT count(*) FROM jobs WHERE chat=?",(a["chat"],)).fetchone()[0]==2
        print("PASS isolated mobile and worker HTTP; two credentials; one executor owns two independent chats; immutable routing, native scopes, offline queues, private provisioning and legacy default")


if __name__=="__main__":run()
