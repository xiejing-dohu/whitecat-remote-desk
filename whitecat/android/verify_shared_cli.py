"""Live acceptance on the dedicated Windows Codex TUI and the paired Android emulator."""
import argparse
import ctypes
import hashlib
import json
import os
import shlex
import sqlite3
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from ctypes import wintypes as W
from pathlib import Path

from verify_native_cli import ROOT,DB,history,projection,worker,native_sync

PHONE="把最初的基数乘二，只回复 PHONE=计算结果"
TERMINAL="Add two to the original base number. Reply only TERM=<number>."
OFFLINE="Add five to the original base number. Reply only OFFLINE=<number>."
BOTH_PHONE="从最初的基数减二，只回复 BOTH_PHONE=计算结果"
BOTH_TERM="Multiply the original base by three. Reply only BOTH_TERM=<number>."
MERGE="把手机上算出的两倍结果减三，只回复 MERGED=计算结果"


def console(pid,message=None):
    args=[sys.executable,"-B","-X","utf8",__file__,"--sample-console",str(pid)]
    if message:args.append(message)
    result=subprocess.check_output(args,stdin=subprocess.DEVNULL).decode("utf8")
    return float(result) if message else result


def console_io(pid,message=None):
    k=ctypes.WinDLL("kernel32",use_last_error=True)
    class Key(ctypes.Structure):
        _fields_=[("down",W.BOOL),("repeat",W.WORD),("vk",W.WORD),("scan",W.WORD),("ch",W.WCHAR),("state",W.DWORD)]
    class Event(ctypes.Structure):
        _fields_=[("type",W.WORD),("key",Key)]
    class Info(ctypes.Structure):
        _fields_=[("size",W._COORD),("cursor",W._COORD),("attr",W.WORD),("window",W.SMALL_RECT),("max",W._COORD)]
    k.FreeConsole()
    if not k.AttachConsole(pid):raise RuntimeError("Dedicated native TUI is no longer open")
    k.CreateFileW.restype=W.HANDLE
    h=W.HANDLE(k.CreateFileW("CONIN$" if message else "CONOUT$",0xc0000000,3,None,3,0,None))
    try:
        if message:
            keys=[]
            for c in message:
                vk=13 if c=="\r" else (ctypes.windll.user32.VkKeyScanW(ord(c)) & 255)
                for down in (1,0):keys.append(Event(1,Key(down,1,vk,0,c,0)))
            got=W.DWORD()
            assert k.WriteConsoleInputW(h,(Event*len(keys))(*keys),len(keys),ctypes.byref(got)) and got.value==len(keys)
            # Codex TUI coalesces a rapid key burst as a paste. Submit with a separate key event.
            time.sleep(.55)
            enter=(Event*2)(Event(1,Key(1,1,13,28,"\r",0)),Event(1,Key(0,1,13,28,"\r",0)))
            assert k.WriteConsoleInputW(h,enter,2,ctypes.byref(got)) and got.value==2
            return time.time()
        i=Info();assert k.GetConsoleScreenBufferInfo(h,ctypes.byref(i))
        lines=[]
        for y in range(max(0,i.window.Top-150),i.window.Bottom+1):
            b=ctypes.create_unicode_buffer(i.size.X+1);got=W.DWORD()
            if k.ReadConsoleOutputCharacterW(h,b,i.size.X,y<<16,ctypes.byref(got)):lines.append(b.value.rstrip())
        return "\n".join(lines)
    finally:k.CloseHandle(h);k.FreeConsole()


def main():
    p=argparse.ArgumentParser();p.add_argument("--thread",required=True);p.add_argument("--terminal-pid",type=int,required=True)
    p.add_argument("--serial",required=True);a=p.parse_args()
    with sqlite3.connect(DB) as db:chat=db.execute("SELECT chat FROM native_links WHERE thread=?",(a.thread,)).fetchone()[0]
    doc=lambda:history(chat)
    baseline=doc()["turns"];assert baseline[0]["prompt"].startswith("白猫双端同聊17")
    screen=console(a.terminal_pid)
    assert "native-demo-17" in screen and any(r["answer"] in screen for r in baseline if r["answer"])
    ctl=[str(Path(os.environ["LOCALAPPDATA"])/"WhitecatAndroidTools/adb/platform-tools/adb.exe"),"-s",a.serial]
    def adb(*parts):return subprocess.check_output(ctl+list(parts),timeout=200)
    def shell(*parts):return adb("shell",shlex.join(parts)).decode("utf8",errors="replace").strip()
    assert shell("getprop","ro.kernel.qemu")=="1"
    def open_app():shell("am","start","-n","dev.whitecat.desk/.ChatActivity")
    def test(stage,**values):
        args=["am","instrument","-w","-e","stage",stage,"-e","chat",chat]
        for k,v in values.items():args+=["-e",k,v]
        result=shell(*args,"dev.whitecat.check/.DeviceCheck")
        if "PASS "+stage not in result:raise AssertionError(result[-450:])
        return dict(l.removeprefix("INSTRUMENTATION_RESULT: ").split("=",1) for l in result.splitlines()
                    if l.startswith("INSTRUMENTATION_RESULT: ") and "=" in l)
    def wait_answer(msg):
        for _ in range(150):
            if any(r["answer"]==msg for r in doc()["turns"]):return
            time.sleep(.7)
        raise AssertionError("Missing "+msg)
    def wait_screen(msg):
        for _ in range(15):
            if msg in console(a.terminal_pid):return
            time.sleep(.5)
        raise AssertionError("Native TUI did not render "+msg)

    prog=ROOT/"shared-cli-acceptance.json"
    evidence={"native_thread":a.thread,"chat":chat,"terminal_pid":a.terminal_pid}
    if prog.exists():
        prev=json.loads(prog.read_text("utf8"))
        if prev.get("native_thread")==a.thread:evidence=prev
    def save():prog.write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+"\n",encoding="utf8")
    if not any(r["prompt"]==PHONE for r in baseline):
        test("shared_select",title=baseline[0]["prompt"][:40],first="基数=41");open_app()
        now=time.time();dev=int(shell("date","+%s%3N"));delta=dev-round((now+time.time())*500)
        target=time.time()+6
        with ThreadPoolExecutor(max_workers=1) as pool:
            pending=pool.submit(test,"shared_send",prompt=PHONE,answer="PHONE=82",at=str(round(target*1000)+delta))
            time.sleep(max(0,target-time.time()));sent=console(a.terminal_pid,TERMINAL)
            phone=pending.result()
        evidence["submit_gap_ms"]=abs(round(sent*1000)-(int(phone["pressed"])-delta));save()
    wait_answer("TERM=43");wait_answer("PHONE=82");open_app()
    print("PASS native terminal remained open for concurrent inputs",flush=True)
    if not any(r["prompt"]==MERGE for r in doc()["turns"]):
        wifi=shell("settings","get","global","wifi_on");data=shell("settings","get","global","mobile_data")
        try:
            adb("reverse","--remove","tcp:18744");shell("svc","wifi","disable");shell("svc","data","disable")
            test("shared_send",prompt=MERGE,offline="1")
            shell("am","force-stop","dev.whitecat.desk");open_app()
            if not any(r["prompt"]==OFFLINE for r in doc()["turns"]):console(a.terminal_pid,OFFLINE)
            wait_answer("OFFLINE=46");wait_screen("OFFLINE=46")
            assert all(r["prompt"]!=MERGE for r in doc()["turns"])
            cache=test("shared_verify",answer="PHONE=82")
            evidence["offline_cache_turns"]=int(cache["turns"])
            assert evidence["offline_cache_turns"]==4
            print("PASS terminal answered while phone was offline and its pending send survived restart",flush=True)
        finally:
            if wifi=="1":shell("svc","wifi","enable")
            if data=="1":shell("svc","data","enable")
            adb("reverse","tcp:18744","tcp:18744");open_app()
    wait_answer("MERGED=79")
    if not any(r["prompt"]==BOTH_PHONE for r in doc()["turns"]):wait_screen("MERGED=79")
    if not any(r["prompt"]==BOTH_PHONE for r in doc()["turns"]):
        open_app()
        now=time.time();dev=int(shell("date","+%s%3N"));delta=dev-round((now+time.time())*500)
        target=time.time()+7
        with ThreadPoolExecutor(max_workers=1) as pool:
            pending=pool.submit(test,"shared_send",prompt=BOTH_PHONE,answer="BOTH_PHONE=39",at=str(round(target*1000)+delta))
            time.sleep(max(0,target-1.1-time.time()));sent=console(a.terminal_pid,BOTH_TERM)
            phone=pending.result()
        evidence["overlap_submit_gap_ms"]=abs(round(sent*1000)-(int(phone["pressed"])-delta));save()
    wait_answer("BOTH_TERM=123");wait_answer("BOTH_PHONE=39");wait_screen("BOTH_PHONE=39")
    local=test("shared_verify",answer="BOTH_PHONE=39");open_app()
    doc_final=doc();rows=doc_final["turns"];digest=projection(doc_final)
    assert len(rows)==8 and local["projection"]==digest["projection"]
    assert doc_final["native_thread"]==a.thread and len({r["id"] for r in rows})==8
    with worker.rollout(a.thread).open("rb") as f:
        native=[v for k,v,_,_ in native_sync.records(f,a.thread,hashlib.sha256()) if k=="turn"]
    assert [(r["prompt"],r["answer"]) for r in rows]==[(r["prompt"],r["answer"]) for r in native]
    with sqlite3.connect(DB) as db:
        mapped=dict(db.execute("SELECT turn,job FROM native_turns WHERE thread=?",(a.thread,)))
        assert [mapped[t["turn"]] for t in native]==[r["id"] for r in rows]
        for prompt in (PHONE,MERGE,BOTH_PHONE):
            row=next(r for r in rows if r["prompt"]==prompt);turn=next(t for t in native if t["prompt"]==prompt)
            assert turn["client_id"]==row["id"] and all(e["label"]!="已启动 Codex CLI" for e in row["events"])
            command=db.execute("SELECT command FROM jobs WHERE id=?",(row["id"],)).fetchone()[0]
            for _ in range(2):
                receipt=json.loads(subprocess.check_output([sys.executable,"-B",str(ROOT.parent/"chat_cli.py"),"send","--chat",chat,"--command",command],input=prompt.encode()))
                assert receipt["job"]==row["id"]
        assert projection(doc())==digest
        from datetime import datetime
        tid=next(t["turn"] for t in native if t["prompt"]==BOTH_TERM)
        marks={}
        with worker.rollout(a.thread).open(encoding="utf8") as f:
            for line in f:
                row=json.loads(line);item=row.get("payload",{})
                if row.get("type")=="event_msg" and item.get("turn_id")==tid and item.get("type") in ("task_started","task_complete"):
                    marks[item["type"]]=datetime.fromisoformat(row["timestamp"].replace("Z","+00:00")).timestamp()
        mobile=next(r for r in rows if r["prompt"]==BOTH_PHONE)
        times=dict(db.execute("SELECT event_key,at FROM events WHERE job=?",(mobile["id"],)))
        assert marks["task_started"]<=times["accepted"]<=times["native.queued"]<marks["task_complete"],(marks,times)
        evidence["overlap_utc_epoch"]={"terminal_started":marks["task_started"],"phone_accepted":times["accepted"],
            "phone_entered_native_queue":times["native.queued"],"terminal_completed":marks["task_complete"]}
        assert evidence["overlap_submit_gap_ms"]<1500
    assert evidence["submit_gap_ms"]<1500
    evidence.update(answers=[r["answer"] for r in rows],native_turns=[r["turn"] for r in native],
                    native_model_turns=len(native),same_process_all_stages=True,phone_cache=digest,
                    phone_reconnect_automatic=True,phone_command_replays=6,duplicate_model_turns=0,
                    apk_sha256=hashlib.sha256((ROOT/"白猫.apk").read_bytes()).hexdigest())
    save()
    (ROOT/"共享终端重连.png").write_bytes(adb("exec-out","screencap","-p"))
    print("PASS one native turn per message and identical Android, API and CLI histories",flush=True)


if __name__=="__main__":
    if sys.argv[1:2]==["--sample-console"]:
        print(console_io(int(sys.argv[2]),sys.argv[3] if len(sys.argv)>3 else None))
    else:main()
