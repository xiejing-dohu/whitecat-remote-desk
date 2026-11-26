"""Start two real worker.py processes on private state. --live-home additionally runs actual Codex turns."""
import argparse
from contextlib import ExitStack,closing
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import time

from verify_agents import serve
from verify_executors import ROOT,request,uid
import executor_registry
import server


def run(live_home=None,timeout=300):
    if live_home and not live_home.is_dir():
        raise SystemExit("Pass an existing, authenticated Codex home.")
    with tempfile.TemporaryDirectory(prefix="whitecat-executors-") as tmp,ExitStack() as stack:
        root=Path(tmp)
        server.STATE=root/"service";server.DB=server.STATE/"desk.sqlite";server.init()
        core=stack.enter_context(serve(server.Handler))
        ingress=stack.enter_context(serve(server.Handler,worker_only=True))
        endpoint=f"http://127.0.0.1:{ingress}"
        config=root/"default.json"
        config.write_text(json.dumps(dict(server=endpoint,executor="default",name="test server",
            spool=str(root/"default-spool"),key_file=str(server.STATE/"worker.key"),native_discovery=False)),encoding="utf8")
        with closing(sqlite3.connect(server.DB)) as db,db:
            extra=executor_registry.enroll(db,"review","Review",root/"review",endpoint)
            executor_registry.enroll(db,"unattended","Offline",root/"offline",endpoint)
        workers=[]
        def stop(p):
            if p.poll() is None:
                p.terminate()
                try:p.wait(timeout=8)
                except subprocess.TimeoutExpired:p.kill();p.wait()
        def launch(file,identity):
            home=live_home.resolve() if live_home else root/("codex-test-"+identity)
            home.mkdir(mode=0o700,exist_ok=True)
            env=dict(os.environ,WHITECAT_STATE=str(server.STATE),CODEX_HOME=str(home),PYTHONIOENCODING="utf-8")
            p=subprocess.Popen([sys.executable,"-B",str(ROOT/"worker.py"),"--config",str(file),"--no-native-discovery"],
                cwd=ROOT,env=env,stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            stack.callback(stop,p);workers.append(p)
        def wait(test,seconds):
            until=time.monotonic()+seconds
            while time.monotonic()<until:
                if any(p.poll() is not None for p in workers):raise AssertionError("One of the dedicated workers exited")
                result=test()
                if result:return result
                time.sleep(.2)
            raise AssertionError("Timed out waiting for dedicated workers")
        def online(identity):
            return any(r["id"]==identity and r["online"] for r in request(core,"/api/executors",operator=True)["executors"])
        def submit(identity,prompt,chat=None):
            return request(core,"/api/jobs",{"executor":identity,"chat":chat,"prompt":prompt,"command":uid()},expected=201,operator=True)
        def doc(chat):
            return request(core,"/api/chats/"+chat,operator=True)
        def answer(receipt,expected):
            def completed():
                row=next(t for t in doc(receipt["chat"])["turns"] if t["id"]==receipt["job"])
                if row["status"] in ("failed","uncertain"):raise AssertionError("Dedicated Codex turn "+row["status"])
                if row["status"]=="done":
                    assert expected in row["answer"]
                    return True
            wait(completed,timeout)
        launch(config,"default");wait(lambda:online("default"),30)
        unattended=submit("unattended","Dedicated test pending")
        if live_home:
            a=submit("review","Reply with just the integer result of 20 plus 7.")
            b=submit("review","Reply with just the integer result of 30 plus 8.")
            time.sleep(2);assert doc(a["chat"])["turns"][0]["status"]=="queued"
        launch(extra["config_path"],"review");wait(lambda:online("review"),30)
        if live_home:
            c=submit("default","Reply with just the integer result of 40 plus 9.")
            answer(a,"27");answer(b,"38");answer(c,"49")
            follow=submit("review","Add one to your previous answer. Reply with just that integer.",a["chat"])
            answer(follow,"28")
            assert len(doc(a["chat"])["turns"])==2 and len(doc(b["chat"])["turns"])==1
            assert doc(a["chat"])["native_thread"]!=doc(b["chat"])["native_thread"] and doc(c["chat"])["executor"]=="default"
        else:
            wait(lambda:(root/"default-spool"/"active.json").exists() and
                        (root/"review"/"spool"/"active.json").exists(),30)
            time.sleep(1.5)
        assert doc(unattended["chat"])["turns"][0]["status"]=="queued"
        print("PASS two independent real worker.py processes, two credentials and spools; offline execution Agent remains queued")
        if live_home:print("PASS three real Codex chats, two owned by review; follow-up returned 28 in the original review chat")


if __name__=="__main__":
    cli=argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--live-home",type=Path,help="Use this authenticated Codex home for new, isolated test conversations")
    cli.add_argument("--timeout",type=int,default=300)
    args=cli.parse_args();run(args.live_home,args.timeout)
