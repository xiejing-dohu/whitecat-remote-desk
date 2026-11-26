"""Exercise Android role management and local-only deletion around a two-device transfer."""
import argparse
import base64
import hashlib
import json
import os
import shlex
import sqlite3
import subprocess
import sys
import time
import uuid
from pathlib import Path

ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT.parent))
import server

def check():
    parser=argparse.ArgumentParser()
    parser.add_argument("phase",choices=["create","finish","verify_hidden"])
    parser.add_argument("--sender",default="127.0.0.1:5581")
    parser.add_argument("--receiver",default="127.0.0.1:5583")
    parser.add_argument("--run",type=Path,required=True)
    args=parser.parse_args()
    adb=Path(os.environ["LOCALAPPDATA"])/"WhitecatAndroidTools/adb/platform-tools/adb.exe"
    def device(serial,*command):
        return subprocess.check_output([str(adb),"-s",serial,*command],timeout=215)
    for dev in (args.sender,args.receiver):
        assert device(dev,"shell","getprop","ro.kernel.qemu").strip()==b"1"
    assert args.sender!=args.receiver
    if args.run.exists():
        session=json.loads(args.run.read_text("utf8"))
    else:
        assert args.phase=="create";suffix=uuid.uuid4().hex[:6]
        session={"agent":"核算助手 "+suffix,"marker":"CAT20_"+suffix.upper(),
                 "question":"工作台验收 "+suffix+"：计算 17 加 25。","draft":"接着核对本次合计的草稿 "+suffix}
        args.run.write_text(json.dumps(session,ensure_ascii=False),encoding="utf8")
    def run(serial,stage):
        command=["am","instrument","-w","-e","stage","workspace_"+stage]
        for key in ("agent","marker","question","draft","chat"):
            if key in session:command+=["-e",key,session[key]]
        command+=["dev.whitecat.check/.DeviceCheck"]
        output=device(serial,"shell",shlex.join(command)).decode("utf8","replace")
        if "PASS workspace_"+stage not in output:
            reason=[s for s in output.splitlines() if s.startswith("INSTRUMENTATION_RESULT:") and ("FAIL" in s or "shortMsg=" in s)]
            raise AssertionError("Device failed "+stage+" "+str(reason[-1:]))
        from collections import defaultdict
        blocks=defaultdict(dict)
        for line in output.splitlines():
            if line.startswith("INSTRUMENTATION_STATUS: design_capture="):
                name,pos,chunk=line.split("=",1)[1].split("|",2)
                if name in ("20-agents.png","20-transfer.png","20-followup.png"):blocks[name][int(pos)]=chunk
            if line.startswith("INSTRUMENTATION_RESULT: chat_id="):session["chat"]=line.split("=",1)[1].strip()
        for name,pieces in blocks.items():
            (args.run.parent/name).write_bytes(base64.b64decode("".join(pieces[i] for i in sorted(pieces)),validate=True))
        session[stage]=True
        args.run.write_text(json.dumps(session,ensure_ascii=False,indent=2),encoding="utf8")
        print("PASS",stage,flush=True)
    def rows(sql,params):
        with sqlite3.connect(server.DB.as_uri()+"?mode=ro",uri=True) as db:return db.execute(sql,params).fetchall()
    if args.phase=="create":
        assert not session.get("chat") and not rows("select id from jobs where prompt=?",(session["question"],))
        run(args.sender,"send")
    else:
        assert session.get("chat")
        if args.phase=="finish":
            if not session.get("delete"):run(args.sender,"delete")
            if not session.get("retained"):run(args.receiver,"retained")
        run(args.sender,"hidden")
    chat=session["chat"]
    for _ in range(30):
        jobs=rows("select id,status,answer from jobs where chat=? order by created",(chat,))
        if jobs and all(s=="done" for _,s,_ in jobs):break
        time.sleep(.4)
    expected=[session["marker"]+"=42"]+([session["marker"]+"=43"] if session.get("retained") else [])
    assert [v.strip() for _,_,v in jobs]==expected,jobs
    assigned=rows("select a.id,c.name,a.archived from chat_agents c join agents a on c.agent=a.id where c.chat=?",(chat,))
    assert len(assigned)==1 and assigned[0][1]==session["agent"]
    if session.get("delete"):assert assigned[0][2]==1
    native=rows("select thread,turn,job from native_turns where job in (select id from jobs where chat=?)",(chat,))
    assert len(native)==len(jobs) and len({n[0] for n in native})==1
    session.update(agent_id=assigned[0][0],jobs=[j[0] for j in jobs],answers=expected,native_turns=[n[1] for n in native],thread=native[0][0])
    args.run.write_text(json.dumps(session,ensure_ascii=False,indent=2),encoding="utf8")
    print("PASS",chat,"native turns",len(native),flush=True)

if __name__=="__main__":check()
