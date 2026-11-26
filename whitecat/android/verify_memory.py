"""Check a dedicated emulator after verify_device.py, before/after restarting its real station."""
import argparse
import hashlib
import json
import os
import shlex
import subprocess
import sys
import time
import uuid
from pathlib import Path

ROOT=Path(__file__).resolve().parent
STATE=Path(os.getenv("WHITECAT_STATE") or Path(os.getenv("LOCALAPPDATA") or Path.home())/"WhitecatDesk")
DRAFT="把最近一次远端结果加一，只回复“同步=数字”，用半角等号。"

def main():
    p=argparse.ArgumentParser()
    p.add_argument("stage",choices=["before","after"]);p.add_argument("--serial",required=True)
    p.add_argument("--worker-pid",type=int,required=True)
    p.add_argument("--spool",type=Path,default=STATE/"station")
    args=p.parse_args()
    adb=[str(Path(os.environ["LOCALAPPDATA"])/"WhitecatAndroidTools/adb/platform-tools/adb.exe"),"-s",args.serial]
    case=json.loads((ROOT/"android-acceptance.json").read_text("utf-8"));chat=case["chat"]
    work=STATE/("memory-check-"+chat+".json")
    binding=next((args.spool/"chats").glob("*/"+chat+"/binding.json"))
    def device(*cmd):
        return subprocess.check_output(adb+list(cmd),timeout=260).decode("utf-8",errors="replace")
    def check(stage,**values):
        cmd=["am","instrument","-w","-e","chat",chat,"-e","stage",stage]
        for k,v in values.items():cmd.extend(["-e",k,v])
        result=device("shell",shlex.join(cmd+["dev.whitecat.check/.DeviceCheck"]))
        fields={}
        for line in result.splitlines():
            if line.startswith("INSTRUMENTATION_RESULT: "):
                k,_,v=line.removeprefix("INSTRUMENTATION_RESULT: ").partition("=")
                fields[k.strip()]=v.strip()
        if "PASS "+stage not in result:raise AssertionError(result[-550:])
        print("Device verified",stage,flush=True)
        return {"turns":int(fields["turns"]),"revision":int(fields["revision"]),"projection":fields["projection"]}
    def cli(*parts,body=None):
        response=subprocess.check_output([sys.executable,"-B",str(ROOT.parent/"chat_cli.py"),*parts],input=body)
        return json.loads(response)
    def projection(history):
        digest=hashlib.sha256()
        for turn in history["turns"]:
            for field in ("id","prompt","answer","status"):digest.update((turn[field]+"\n").encode())
        return {"turns":len(history["turns"]),"revision":history["revision"],"projection":digest.hexdigest()}
    if device("shell","getprop","ro.kernel.qemu").strip()!="1":raise RuntimeError("Requires the acceptance emulator")
    proof=json.loads(work.read_text("utf-8")) if args.stage=="after" else {}
    try:
        if args.stage=="before":
            mapped=json.loads(binding.read_text("utf-8"));history=cli("history",chat)
            assert len(history["turns"])==2 and mapped["head"]==history["turns"][-1]["id"] and mapped["digest"]
            mobile=check("draft",answer="复核=151",draft=DRAFT)
            assert mobile==projection(history)
            proof.update(chat=chat,cli_thread=mapped["thread"],first_projection=mobile,before_pid=args.worker_pid,
                         remote_command=str(uuid.uuid4()),apk_sha256=case["apk_sha256"])
            work.write_text(json.dumps(proof),encoding="utf-8")
        else:
            assert args.worker_pid!=proof["before_pid"]
            assert json.loads(binding.read_text("utf-8"))["thread"]==proof["cli_thread"]
            device("reverse","--remove","tcp:18744")
            receipt=cli("send","--chat",chat,"--command",proof["remote_command"],
                        body="把刚才的复核结果减去 10，只回复“远端=数字”，用半角等号。".encode())
            assert receipt["chat"]==chat
            end=time.monotonic()+150
            while time.monotonic()<end:
                hist=cli("history",chat)
                last=hist["turns"][-1]
                if last["status"] in ("done","failed","uncertain"):break
                time.sleep(1)
            assert last["id"]==receipt["job"] and last["answer"].strip()=="远端=141",last["status"]
            assert len(hist["turns"])==3
            device("shell","am","force-stop","dev.whitecat.desk")
            offline=check("cached",answer="复核=151",draft=DRAFT)
            assert offline==proof["first_projection"]
            device("reverse","tcp:18744","tcp:18744")
            synced=check("reconcile",remote="远端=141",draft=DRAFT,answer="同步=142")
            history=cli("history",chat);assert synced==projection(history) and synced["turns"]==4
            mapped=json.loads(binding.read_text("utf-8"))
            assert mapped["head"]==history["turns"][-1]["id"] and mapped["thread"]==proof["cli_thread"] and mapped["digest"]
            mode=[]
            for turn in history["turns"]:
                labels=[event["label"] for event in turn["events"]]
                assert labels.count("已启动 Codex CLI")==1
                mode.append("resume" if "已恢复原 CLI 历史" in labels else "new" if "已建立 CLI 历史" in labels else "rebuild")
            assert mode==["new","resume","resume","resume"],mode
            proof.update(after_pid=args.worker_pid,offline_projection=offline,final_projection=synced,modes=mode,
                         final_head=mapped["head"],remote_answer="远端=141",phone_answer="同步=142",draft_survived=True,
                         activity="Android 11 API 30 / 14d Windows station")
            (ROOT/"memory-acceptance.json").write_text(json.dumps(proof,indent=2,ensure_ascii=False)+"\n",encoding="utf-8")
        print("PASS "+args.stage,flush=True)
    finally:
        device("reverse","tcp:18744","tcp:18744")
        device("shell","am","start","-n","dev.whitecat.desk/.ChatActivity")

if __name__=="__main__":
    main()
