"""Exercise authenticated Android SSE on a real Codex TUI while disconnecting the emulator."""
import argparse
import hashlib
import json
import os
import queue
import shlex
import sqlite3
import subprocess
import sys
import threading
import time
from pathlib import Path

from verify_native_cli import ROOT,DB,history,projection,worker,native_sync
from verify_shared_cli import console

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("--chat",required=True);ap.add_argument("--serial",required=True);ap.add_argument("--terminal-pid",required=True,type=int)
    ap.add_argument("--marker",required=True);ap.add_argument("--resume-queued",action="store_true")
    args=ap.parse_args();chat=args.chat;marker=args.marker
    baseline=history(chat);thread=baseline["native_thread"];before=len(baseline["turns"])
    assert baseline["turns"][0]["prompt"].startswith("白猫流式验收18")
    prior=[t for t in baseline["turns"] if marker in t["prompt"]]
    if args.resume_queued:
        assert len(prior)==1 and prior[0]["status"] in ("queued","running");before-=1
    else:assert not prior
    ctl=[str(Path(os.environ["LOCALAPPDATA"])/"WhitecatAndroidTools/adb/platform-tools/adb.exe"),"-s",args.serial]
    def adb(*parts):return subprocess.check_output(ctl+list(parts),timeout=90)
    def shell(*parts):return adb("shell",shlex.join(parts)).decode("utf8",errors="replace").strip()
    def open_app():shell("am","start","-n","dev.whitecat.desk/.ChatActivity")
    assert shell("getprop","ro.kernel.qemu")=="1"
    def cmd(stage,**kw):
        parts=["am","instrument","-w","-e","chat",chat,"-e","stage",stage]
        for k,val in kw.items():parts.extend(["-e",k,val])
        return parts+["dev.whitecat.check/.DeviceCheck"]
    prompt=(marker+"。请在当前工作目录做一次分批核算。先用一条简短的用户可见进度消息说明开始，然后依次调用当前会话的 whitecat_audit 工具 audit_csv 三次，参数 stage 分别为 count、sum、check。每次等待工具真实返回再开始下一步；各步之间用一句中文进度说明本步结果。文件名为 daily.csv。最后输出一份约 700 个汉字的核对说明，说明行数、数量合计、零值数和复核方式，最后单独一行按实际结果写出 ROWS=数字 TOTAL=数字 ZERO=数字。")
    samples=[];out=queue.Queue();last_output=[]
    def start_watch(label,**extras):
        proc=subprocess.Popen(ctl+["shell",shlex.join(cmd("stream_watch",marker=marker,**extras))],stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
        def read():
            for raw in proc.stdout:
                line=raw.decode("utf8",errors="replace").strip()
                if line.startswith("INSTRUMENTATION_STATUS: sample="):
                    seq,words,tools,active,partial,status,presence=line.split("=",1)[1].split("|",6)
                    sample=dict(phase=label,cursor=int(seq),visible_chars=int(words),tools=int(tools),
                                running_tools=int(active),partial_final=partial=="true",status=status,presence=presence)
                    samples.append(sample);out.put(sample)
                elif line.startswith("INSTRUMENTATION_RESULT:"):
                    last_output.append(line)
                    if "FAIL " in line:out.put({"failure":line})
        threading.Thread(target=read,daemon=True).start();return proc
    def matching(predicate,seconds):
        end=time.monotonic()+seconds
        while time.monotonic()<end:
            try:s=out.get(timeout=min(1,end-time.monotonic()))
            except queue.Empty:continue
            if "failure" in s:raise AssertionError(s["failure"])
            if predicate(s):return s
        raise AssertionError("Timed out observing Android stream: "+str(samples[-2:])+str(last_output[-2:]))
    def screenshot(name):(ROOT/name).write_bytes(adb("exec-out","screencap","-p"))
    def query(sql,params):
        with sqlite3.connect(DB.as_uri()+"?mode=ro",uri=True) as db:return db.execute(sql,params).fetchall()
    wifi=shell("settings","get","global","wifi_on");data=shell("settings","get","global","mobile_data")
    open_app()
    if args.resume_queued:assert prior[0]["prompt"]==prompt
    first=start_watch("before_drop",**({} if args.resume_queued else {"newPrompt":prompt}))
    try:
        active=matching(lambda s:s["running_tools"]>0 and s["status"]=="running",75)
        screenshot("流式执行中.png")
        print("PASS Android rendered active native tool at cursor",active["cursor"],flush=True)
        adb("reverse","--remove","tcp:18744");shell("svc","wifi","disable");shell("svc","data","disable")
        # An already-established adb reverse socket can outlive removal. Stopping the App closes it.
        shell("am","force-stop","dev.whitecat.desk");first.wait(timeout=12)
        second=start_watch("reconnected")
        offline=matching(lambda s:"本机缓存" in s["presence"],30)
        end=time.monotonic()+65
        while time.monotonic()<end:
            row=query("SELECT max(seq) FROM stream_cursor WHERE chat=?",(chat,))[0][0]
            if row>offline["cursor"]+2:break
            time.sleep(.25)
        assert row>offline["cursor"] and history(chat)["turns"][-1]["status"]=="running"
        screenshot("流式断线缓存.png")
        print("PASS native task advanced while Android retained committed cursor",offline["cursor"],"->",row,flush=True)
    finally:
        if wifi=="1":shell("svc","wifi","enable")
        if data=="1":shell("svc","data","enable")
        adb("reverse","tcp:18744","tcp:18744")
    resumed=matching(lambda s:s["phase"]=="reconnected" and "实时同步" in s["presence"] and s["cursor"]>offline["cursor"] and s["status"]=="running",40)
    partial=resumed if resumed["partial_final"] else matching(lambda s:s["partial_final"] and s["visible_chars"]>0,155)
    screenshot("流式正文生成中.png")
    done=matching(lambda s:s["status"]=="done",100);second.wait(timeout=20);open_app()
    assert any("PASS stream_watch" in l for l in last_output),last_output[-2:]
    doc=history(chat);final=doc["turns"][-1];assert len(doc["turns"])==before+1 and final["prompt"]==prompt
    assert "ROWS=42 TOTAL=126 ZERO=6" in final["answer"],final["answer"][-100:]
    rows=query("SELECT thread,turn FROM native_turns WHERE job=?",(final["id"],));assert len(rows)==1 and rows[0][0]==thread
    turn=rows[0][1]
    with worker.rollout(thread).open("rb") as f:
        native=[v for kind,v,*_ in native_sync.records(f,thread,hashlib.sha256()) if kind=="turn" and v["prompt"]==prompt]
    assert len(native)==1 and native[0]["turn"]==turn and native[0]["answer"]==final["answer"]
    parts=final["parts"];ids=[p["item"] for p in parts];assert len(set(ids))==len(ids)
    tools=[p for p in parts if p["kind"] not in ("answer","commentary")]
    assert len(tools)==3 and all(p["status"]=="done" for p in tools)
    assert any(p["body"]==final["answer"] for p in parts if p["kind"]=="answer")
    assert any(projection(doc)["projection"] in line for line in last_output)
    assert "ROWS=42 TOTAL=126 ZERO=6" in console(args.terminal_pid)
    command=query("SELECT command FROM jobs WHERE id=?",(final["id"],))[0][0]
    for _ in range(2):
        response=json.loads(subprocess.check_output([sys.executable,"-B",str(ROOT.parent/"chat_cli.py"),"send","--chat",chat,"--command",command],input=prompt.encode()))
        assert response["job"]==final["id"]
    assert projection(history(chat))==projection(doc)
    package=shell("pm","path","dev.whitecat.desk").removeprefix("package:")
    installed_hash=shell("sha256sum",package).split()[0]
    apk_hash=hashlib.sha256((ROOT/"白猫.apk").read_bytes()).hexdigest()
    assert installed_hash==apk_hash
    assert all(p["label"]=="audit_csv" and 0<p["started_ms"]<=p["ended_ms"] for p in tools)
    evidence={"thread":thread,"chat":chat,"job":final["id"],"turn":turn,"terminal_pid":args.terminal_pid,
              "before_drop":active,"offline":offline,"backend_cursor_while_offline":row,"resumed_while_running":resumed,
              "partial_final_on_phone":partial,"done":done,"observations":samples,
              "tools":[{k:p[k] for k in ("item","kind","label","status","progress","started_ms","ended_ms")} for p in tools],
              "matching_native_executions":1,"duplicate_submits":2,"answer_characters":len(final["answer"]),
              "answer_sha256":hashlib.sha256(final["answer"].encode()).hexdigest(),"android_projection":projection(doc),
              "partial_observations":sum(s["partial_final"] for s in samples),
              "installed_apk_sha256":installed_hash,"apk_sha256":apk_hash}
    (ROOT/"stream-acceptance.json").write_text(json.dumps(evidence,indent=2,ensure_ascii=False)+"\n",encoding="utf8")
    time.sleep(.5);screenshot("流式续传完成.png")
    print("PASS Android partial text, durable resume, matching native result and one execution",flush=True)

if __name__=="__main__":main()
