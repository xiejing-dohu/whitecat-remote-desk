"""Drive the ordinary interactive Windows Codex CLI for the emulator acceptance, via a private PTY."""
import argparse
import collections
import json
import os
import re
import sqlite3
import threading
import time
from pathlib import Path
from winpty import PtyProcess

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--work",type=Path,required=True)
    parser.add_argument("--resume")
    parser.add_argument("--prompt",required=True)
    parser.add_argument("--expect",required=True)
    parser.add_argument("--result",type=Path,required=True)
    args=parser.parse_args()
    args.work.mkdir(parents=True,exist_ok=True)
    exe=next((Path(os.environ["APPDATA"])/"npm/node_modules/@openai/codex/node_modules").glob("@openai/codex-win32-*/vendor/*/bin/codex.exe"))
    index=Path(os.environ.get("CODEX_HOME") or Path.home()/".codex")/"state_5.sqlite"
    env={k:v for k,v in os.environ.items() if k not in ("CODEX_THREAD_ID","CODEX_INTERNAL_ORIGINATOR_OVERRIDE")}
    env["TERM"]="xterm-256color"
    cmd=[str(exe),"--no-alt-screen","--sandbox","read-only","--ask-for-approval","never"]
    if args.resume:cmd.extend(["resume",args.resume])
    cmd.append(args.prompt)
    start=time.time()
    proc=PtyProcess.spawn(cmd,cwd=str(args.work),env=env,dimensions=(40,115))
    recent=collections.deque(maxlen=150);stop=threading.Event()
    def drain():
        try:
            while not stop.is_set():
                value=proc.read(8192);recent.append(value)
                if "\x1b[6n" in value:proc.write("\x1b[1;1R")
                if "\x1b]11;?" in value:proc.write("\x1b]11;rgb:0000/0000/0000\x1b\\")
        except (EOFError,OSError):pass
    threading.Thread(target=drain,daemon=True).start()
    try:
        end=start+155;confirmed=False
        while time.time()<end:
            screen=re.sub(r"\x1b\[[0-?]*[ -/]*[@-~]","","".join(recent))
            if not confirmed and ("trust this folder" in screen.lower() or "trust the contents" in screen.lower() or "trust this directory" in screen.lower()):
                proc.write("\r");confirmed=True
            with sqlite3.connect(index.as_uri()+"?mode=ro",uri=True) as db:
                if args.resume:
                    rows=db.execute("select id,rollout_path,source from threads where id=?",(args.resume,)).fetchall()
                else:
                    rows=db.execute("select id,rollout_path,source from threads where lower(cwd) in (lower(?),lower(?)) and created_at>=?",
                                    (str(args.work),"\\\\?\\"+str(args.work),int(start)-2)).fetchall()
            for ident,path,source in rows:
                if not Path(path).is_file():continue
                turn=None
                for line in Path(path).open(encoding="utf-8"):
                    try:row=json.loads(line)
                    except ValueError:continue
                    data=row.get("payload",{})
                    if row.get("type")=="event_msg" and data.get("type")=="task_complete" and (data.get("last_agent_message") or "").strip()==args.expect:
                        turn=data.get("turn_id")
                if turn:
                    args.result.write_text(json.dumps({"thread":ident,"turn":turn,"source":source,"answer":args.expect},ensure_ascii=False),encoding="utf-8")
                    print("Interactive CLI completed",source,ident,flush=True)
                    # Exit normally so the native writer lease is released before handing off.
                    proc.write("/exit");time.sleep(.2);proc.write("\r")
                    for _ in range(30):
                        if not proc.isalive():break
                        time.sleep(.15)
                    return
            if not proc.isalive():break
            time.sleep(0.65)
        tail=re.sub(r"[\x00-\x1f]"," ",screen[-1100:])
        # Only the dedicated synthetic demo is used with this driver.
        raise RuntimeError("interactive acceptance did not complete: "+tail)
    finally:
        stop.set()
        if proc.isalive():proc.terminate(force=True)

if __name__=="__main__":main()
