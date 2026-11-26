"""Acceptance against an installed Android emulator, real interactive Codex TUI and live Whitecat service."""
import argparse
import hashlib
import json
import os
import shlex
import sqlite3
import subprocess
import sys
import time
from pathlib import Path
from urllib.request import Request,urlopen

ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT.parent))
import native_sync
import worker
from server import DB,STATE

FIRST="白猫原生对话验收"
DRAFT="把终端得到的结果减去三，只回复 合流=计算出的数字"
def history(chat):
    return json.loads(subprocess.check_output([sys.executable,"-B",str(ROOT.parent/"chat_cli.py"),"history",chat]))
def projection(doc):
    digest=hashlib.sha256()
    for row in doc["turns"]:
        for key in ("id","prompt","answer","status"):digest.update((row[key]+"\n").encode())
    return {"turns":len(doc["turns"]),"revision":doc["revision"],"projection":digest.hexdigest()}

def crosscheck_native(evidence):
    thread=evidence["native_thread"];chat=evidence["chat"];doc=history(chat)
    with worker.rollout(thread).open("rb") as f:
        native=[row for kind,row,_,_ in native_sync.records(f,thread,hashlib.sha256()) if kind=="turn"]
    expected=[(t["prompt"],t["answer"]) for t in doc["turns"]]
    assert len(expected)==4 and [(t["prompt"],t["answer"]) for t in native]==expected
    with sqlite3.connect(DB.as_uri()+"?mode=ro",uri=True) as db:
        mapping=dict(db.execute("SELECT turn,job FROM native_turns WHERE thread=?",(thread,)))
        final=doc["turns"][-1]
        command=db.execute("SELECT command FROM jobs WHERE id=? AND chat=?",(final["id"],chat)).fetchone()[0]
    assert [mapping[t["turn"]] for t in native]==[t["id"] for t in doc["turns"]]
    response=json.loads(subprocess.check_output([sys.executable,"-B",str(ROOT.parent/"chat_cli.py"),"send",
                   "--chat",chat,"--command",command],input=final["prompt"].encode("utf8")))
    assert response["job"]==final["id"] and projection(history(chat))==evidence["after_reconnect"]
    evidence.update(native_turn_ids=[t["turn"] for t in native],native_transcript_matches_phone=True,
                    phone_command_replay_same_job=response["job"])
    return evidence

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--serial",required=True)
    parser.add_argument("--demo",type=Path,required=True,help="Dedicated demo directory with native_tui.py first.json")
    args=parser.parse_args()
    ctl=[str(Path(os.environ["LOCALAPPDATA"])/"WhitecatAndroidTools/adb/platform-tools/adb.exe"),"-s",args.serial]
    def adb(*parts):return subprocess.check_output(ctl+list(parts),timeout=270)
    def shell(*parts):return adb("shell",shlex.join(parts)).decode("utf8",errors="replace").strip()
    if shell("getprop","ro.kernel.qemu")!="1":raise RuntimeError("Dedicated emulator required")
    first=json.loads((args.demo/"first.json").read_text("utf8"));thread=first["thread"]
    with sqlite3.connect(DB.as_uri()+"?mode=ro",uri=True) as db:
        chat=db.execute("SELECT chat FROM native_links WHERE thread=?",(thread,)).fetchone()[0]
    def test(stage,**values):
        command=["am","instrument","-w","-e","stage",stage,"-e","chat",chat]
        for key,value in values.items():command.extend(["-e",key,value])
        output=shell(*command,"dev.whitecat.check/.DeviceCheck")
        if "PASS "+stage not in output:raise AssertionError(output[-420:])
        fields=dict(line.removeprefix("INSTRUMENTATION_RESULT: ").split("=",1) for line in output.splitlines()
                    if line.startswith("INSTRUMENTATION_RESULT: ") and "=" in line)
        return {"turns":int(fields["turns"]),"revision":int(fields["revision"]),"projection":fields["projection"]}
    baseline=history(chat)
    assert len(baseline["turns"])==1 and baseline["turns"][0]["answer"]=="起点=37"
    pair=test("native_open",title=baseline["turns"][0]["prompt"][:40],first="起点=37",prompt="把起始值乘以二，只回复 手机=计算出的数字",answer="手机=74",draft=DRAFT)
    assert pair==projection(history(chat)) and pair["turns"]==2
    print("PASS interactive CLI -> Android follow-up, two actual turns",flush=True)
    wifi=shell("settings","get","global","wifi_on")
    data=shell("settings","get","global","mobile_data")
    try:
        adb("reverse","--remove","tcp:18744");shell("svc","wifi","disable");shell("svc","data","disable")
        shell("am","force-stop","dev.whitecat.desk");shell("am","start","-n","dev.whitecat.desk/.ChatActivity")
        early=test("native_offline",answer="手机=74",draft=DRAFT)
        assert early==pair
        shell("am","start","-n","dev.whitecat.desk/.ChatActivity")
        print("PASS immediate offline process restart retained both turns and draft",flush=True)
        subprocess.run([sys.executable,"-B","-X","utf8",str(ROOT/"test/native_tui.py"),"--work",str(args.demo/"terminal"),
                        "--resume",thread,"--prompt","把手机上一轮算出的数加五，只回复 终端=计算出的数字","--expect","终端=79",
                        "--result",str(args.demo/"third.json")],check=True,timeout=195)
        third=json.loads((args.demo/"third.json").read_text("utf8"))
        assert third["thread"]==thread
        for _ in range(30):
            remote=history(chat)
            if len(remote["turns"])==3:break
            time.sleep(1)
        assert len(remote["turns"])==3 and remote["turns"][-1]["answer"]=="终端=79"
        offline=test("native_offline",answer="手机=74",draft=DRAFT)
        assert offline==pair
        # Replay the native delivery twice (e.g. POST committed and its HTTP receipt was lost).
        with worker.rollout(thread).open("rb") as f:
            entry=next(item for kind,item,_,_ in native_sync.records(f,thread,hashlib.sha256())
                       if kind=="turn" and item["turn"]==third["turn"])
        key=(STATE/"worker.key").read_text().strip();receipt=[]
        for _ in range(2):
            req=Request("http://127.0.0.1:18742/worker/native-import",json.dumps(dict(entry,thread=thread,chat=chat)).encode(),
                        {"Content-Type":"application/json","Authorization":"Bearer "+key})
            with urlopen(req,timeout=10) as response:receipt.append(json.load(response)["job"])
        assert receipt==[remote["turns"][-1]["id"]]*2 and projection(history(chat))==projection(remote)
        print("PASS offline native follow-up, cached draft and duplicate-import replay",flush=True)
    finally:
        if wifi=="1":shell("svc","wifi","enable")
        if data=="1":shell("svc","data","enable")
        adb("reverse","tcp:18744","tcp:18744")
        shell("am","start","-n","dev.whitecat.desk/.ChatActivity")
    final=test("reconcile",remote="终端=79",draft=DRAFT,answer="合流=76")
    doc=history(chat)
    assert final==projection(doc) and final["turns"]==4 and doc["native_thread"]==thread
    answers=[r["answer"] for r in doc["turns"]];assert answers==["起点=37","手机=74","终端=79","合流=76"]
    starts=[[e["label"] for e in r["events"]].count("已启动 Codex CLI") for r in doc["turns"]]
    assert starts==[0,1,0,1] and len(set(r["id"] for r in doc["turns"]))==4
    assert all(any(e["label"]=="已恢复原 CLI 历史" for e in doc["turns"][i]["events"]) for i in (1,3))
    evidence={"device":shell("getprop","ro.product.model"),"chat":chat,"native_thread":thread,
              "native_originator":"codex-tui","answers":answers,"daemon_cli_launches_per_turn":starts,
              "pre_disconnect":pair,"offline_after_remote_turn":offline,"after_reconnect":final,
              "two_import_replays_same_job":receipt[0],"checkpoint_after_duplicate_unchanged":True,
              "same_thread_all_four":True,"apk_sha256":hashlib.sha256((ROOT/"白猫.apk").read_bytes()).hexdigest()}
    crosscheck_native(evidence)
    (ROOT/"native-cli-acceptance.json").write_text(json.dumps(evidence,indent=2,ensure_ascii=False)+"\n",encoding="utf8")
    shell("am","start","-W","-n","dev.whitecat.desk/.ChatActivity");time.sleep(2)
    (ROOT/"原生CLI双向续聊.png").write_bytes(adb("exec-out","screencap","-p"))
    print("PASS four ordered turns in one real native thread",chat,flush=True)

if __name__=="__main__":main()
