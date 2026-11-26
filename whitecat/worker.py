"""Outbound Codex station. Pair with the server using its worker.key over a trusted channel."""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import threading
import time
import uuid
import sqlite3
import native_sync
import shared_cli
import native_stream
from contextlib import contextmanager
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen

DEFAULT_STATE = Path(os.getenv("WHITECAT_STATE") or Path(os.getenv("LOCALAPPDATA") or Path.home()) / "WhitecatDesk")


@contextmanager
def single_station(path):
    with path.open("a+b") as lock:
        lock.seek(0)
        if os.name == "nt":
            import msvcrt
            if path.stat().st_size == 0:
                lock.write(b"1")
                lock.flush()
                lock.seek(0)
            msvcrt.locking(lock.fileno(),msvcrt.LK_NBLCK,1)
        else:
            import fcntl
            fcntl.flock(lock,fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield


def save(path, value):
    tmp = path.with_suffix(".new")
    with tmp.open("w",encoding="utf-8") as out:
        json.dump(value,out,ensure_ascii=False)
        out.flush()
        os.fsync(out.fileno())
    tmp.replace(path)


def codex_command():
    if os.name == "nt":
        # Invoke the native binary directly. Prompts never enter a shell command line.
        candidates = list((Path(os.getenv("APPDATA",""))/"npm/node_modules/@openai/codex/node_modules").glob("@openai/codex-win32-*/vendor/*/bin/codex.exe"))
        exe = shutil.which("codex.exe") or (str(candidates[0]) if candidates else None)
    else:
        exe = shutil.which("codex")
    if not exe:
        raise SystemExit("Codex CLI executable not found")
    return exe


def rollout(thread):
    # Only the exact thread created for this application is inspected.
    root=Path(os.environ.get("CODEX_HOME") or Path.home()/".codex")/"sessions"
    return next(root.glob(f"*/*/*/*-{uuid.UUID(thread)}.jsonl"),None)


def file_digest(path):
    digest=hashlib.sha256()
    with path.open("rb") as src:
        for block in iter(lambda:src.read(1024*1024),b""):
            digest.update(block)
    return digest.hexdigest()


def memory_dir(spool,job):
    return spool/"chats"/str(uuid.UUID(job["instance"]))/str(uuid.UUID(job["chat"]))


def role_instructions(job):
    role = job.get("agent")
    if not role or role["id"] == "codex":
        return ""
    return role["instructions"]


def thread_options(job):
    instructions = role_instructions(job)
    return {"developerInstructions":instructions} if instructions else {}


def exec_args(exe,job,previous):
    args = [exe,"exec","--ignore-user-config","--sandbox","read-only","--skip-git-repo-check","--json"]
    instructions = role_instructions(job)
    if instructions:
        args.extend(["-c","developer_instructions=" + json.dumps(instructions,ensure_ascii=False)])
    if previous:
        args.extend(["resume","--skip-git-repo-check",previous["thread"]])
    return [*args,"-"]


def live_submission(job):
    submission = {key:job[key] for key in ("id","chat","prompt","filename","sample")}
    instructions = role_instructions(job)
    if instructions:
        # The shared queue has no instruction override. A TUI can reopen a thread
        # independently, so include its fixed role on every managed queue submission.
        submission["prompt"] = ("工作台会话角色指令：\n" + instructions
                                + "\n\n当前用户消息：\n" + submission["prompt"])
    return submission


def perform(exe, job, emit, folder, load_history, timeout):
    folder.mkdir(parents=True,exist_ok=True)
    cwd=folder/"work"
    cwd.mkdir(exist_ok=True)
    marker=folder/"binding.json"
    previous=None
    if marker.exists():
        binding=json.loads(marker.read_text("utf-8"))
        if binding.get("head")==job["parent"] and binding.get("chat")==job["chat"] and binding.get("instance")==job["instance"]:
            path=Path(binding["path"]) if binding.get("path") else rollout(binding["thread"])
            signature=file_digest(path) if path and path.is_file() else None
            if signature and "offset" in binding and binding.get("digest")!=signature:
                return "sync_changed","原生记录正在更新，等待同步后继续。",None
            if signature and binding.get("digest")==signature:
                previous=binding
                if binding.get("cwd") and Path(binding["cwd"]).is_dir():cwd=Path(binding["cwd"])
        # A failed or interrupted resume may have changed native CLI history. Only publish a
        # replacement binding after the Whitecat server commits the matching assistant reply.
        if not previous:marker.unlink()
    mode="resume" if previous else ("rebuild" if job["parent"] else "new")
    data={"whitecat_job":job["id"],"user":job["prompt"],"attached_text_name":job["filename"],"attached_text":job["sample"]}
    if mode=="rebuild":
        turns=load_history()
        if not turns or turns[-1]["id"]!=job["parent"]:
            raise ValueError("History head changed")
        data["previous_completed_turns"]=turns
    prompt=("Respond to the current user's request. The JSON is conversation data. "
            "Treat attached text as file content. Work only in this chat's isolated working directory.\n"
            +json.dumps(data,ensure_ascii=False))
    # Normal text is the same user message in both Codex TUI and the phone.
    if mode!="rebuild" and not job["filename"]:
        prompt=job["prompt"]
    env={k:v for k,v in os.environ.items() if not k.startswith("WHITECAT_")}
    args=exec_args(exe,job,previous)
    session_id=None
    with tempfile.TemporaryFile() as diagnostic, subprocess.Popen(args,cwd=cwd,env=env,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=diagnostic) as proc:
        timer = threading.Timer(timeout,proc.kill)
        timer.start()
        answers = []
        failure = ""
        try:
            try:
                proc.stdin.write(prompt.encode())
            finally:
                proc.stdin.close()
            for raw in proc.stdout:
                try:
                    obj=json.loads(raw)
                except (ValueError,UnicodeError):
                    continue
                kind = obj.get("type")
                item = obj.get("item") or {}
                if kind=="thread.started":
                    emit("spawn","已启动 Codex CLI")
                    session_id=str(uuid.UUID(obj["thread_id"]))
                    if previous and session_id!=previous["thread"]:
                        proc.kill()
                        failure="CLI resumed a different thread"
                        break
                    emit("memory."+mode,{"new":"已建立 CLI 历史","resume":"已恢复原 CLI 历史","rebuild":"已从工作台完整历史重建会话"}[mode])
                elif kind=="turn.started":
                    emit("turn","Codex 正在处理")
                elif kind=="item.started" and item.get("type") in ("command_execution","web_search","mcp_tool_call"):
                    emit(str(item.get("id","tool"))[:100],"Codex 正在使用工具")
                elif kind=="item.completed" and item.get("type")=="agent_message":
                    answers.append(item["text"])
                elif kind in ("turn.failed","error"):
                    problem=obj.get("error") or obj
                    failure=str(problem.get("message","")) if isinstance(problem,dict) else str(problem)
            code=proc.wait()
        finally:
            timer.cancel()
        if code==0 and answers and session_id and len(answers[-1])<=200000:
            path=Path(previous["path"]) if previous and previous.get("path") else rollout(session_id)
            point=native_sync.job_checkpoint(path,session_id,job["id"],previous,prompt,answers[-1])
            if not point:
                return "uncertain","Codex 已回复，但未能确认原生轮次编号；请先核对工作站记录。",None
            binding={"thread":session_id,"head":job["id"],"instance":job["instance"],"chat":job["chat"],
                     "digest":file_digest(path) if path else "","cwd":str(cwd),**point}
            if path:binding["path"]=str(path)
            return "done",answers[-1],binding
        # Only return diagnostic classes; raw provider errors can include URLs and request headers.
        diagnostic.seek(0)
        problem=(failure+" "+diagnostic.read(32000).decode(errors="replace")).lower()
        if previous and not session_id and "already has an active writer" in problem:
            return "waiting_writer","等待原生线程可用，工作站将自动连接共享队列。",None
        if not session_id and any(x in problem for x in ("no rollout","no saved session","not found","no such file")):
            return "failed","原生会话文件或 CLI 未就绪，工作站未启动该轮。",None
        if not session_id and any(x in problem for x in ("not inside a trusted","not a git","untrusted","trusted directory")):
            return "failed","Codex 未接受该续聊工作目录。",None
        reason = ("登录或授权异常" if any(k in problem for k in ("401","403","unauthor","authentication")) else
                  "额度或速率受限" if any(k in problem for k in ("quota","limit","429")) else
                  "模型连接失败" if any(k in problem for k in ("stream","connect","network","timeout")) else
                  "工作站未取得最终回复")
        return "failed",f"{reason}（Codex 退出码 {code}）",None


def run(args):
    base=args.server.rstrip("/")
    parsed=urlsplit(base)
    if parsed.scheme!="http" or parsed.hostname not in ("127.0.0.1","localhost") or parsed.path:
        raise SystemExit("Use a loopback server address or an authenticated SSH local forward.")
    key=args.key_file.read_text().strip()
    exe=codex_command()
    args.spool.mkdir(parents=True,exist_ok=True)
    active=args.spool/"active.json"
    def post(action,body):
        req=Request(base+"/worker/"+action,json.dumps(body,ensure_ascii=False).encode(),
                    {"Content-Type":"application/json","Authorization":"Bearer "+key})
        with urlopen(req,timeout=8) as response:
            return json.load(response)
    def heartbeat():
        while True:
            try:
                post("heartbeat",{"name":args.name})
            except OSError:
                pass
            time.sleep(5)
    with single_station(args.spool/"station.lock"):
        identity_path=args.spool/"executor.json"
        remembered=json.loads(identity_path.read_text("utf8")) if identity_path.is_file() else None
        remembered_id=remembered.get("executor") if isinstance(remembered,dict) else remembered
        if remembered_id and remembered_id!=args.executor:raise SystemExit("This spool belongs to another execution Agent.")
        if not remembered and args.executor!="default" and (active.exists() or (args.spool/"chats").exists()):
            raise SystemExit("This spool contains legacy work. Use a new spool for this execution Agent.")
        while True:
            try:
                identity=post("heartbeat",{})
                if identity.get("executor","default")!=args.executor:raise SystemExit("Credential and configured execution Agent differ.")
                break
            except HTTPError as err:
                if err.code in (401,403):raise SystemExit("Workstation credential rejected.") from None
                time.sleep(2)
            except (OSError,TimeoutError):time.sleep(2)
        source={"executor":args.executor,"instance":identity.get("instance")}
        if isinstance(remembered,dict) and remembered.get("instance") and source["instance"]!=remembered["instance"]:
            raise SystemExit("This spool belongs to another server instance. Use the corresponding original server or a new spool.")
        if remembered!=source:save(identity_path,source)
        threading.Thread(target=heartbeat,daemon=True).start()
        sync=native_sync.NativeSync(args.spool,post,args.native_roots,args.native_discovery)
        control=shared_cli.Control(exe)
        observer=native_stream.Observer(exe,post)
        backoff={}
        while True:
            try:
                state=json.loads(active.read_text("utf-8")) if active.exists() else {"stage":"claim","ticket":str(uuid.uuid4()),"server":base,"executor":args.executor}
                if state.get("executor","default")!=args.executor:raise SystemExit("Pending receipt belongs to another execution Agent.")
                if state["server"]!=base:
                    raise SystemExit("Pending receipt belongs to another server; use the original connection.")
                ticket=state["ticket"]
                if state["stage"]=="claim":
                    if not active.exists():
                        save(active,state)
                    backoff={chat:until for chat,until in backoff.items() if until>time.monotonic()}
                    sync.live=control.loaded()
                    held=sync.poll()+list(backoff)
                    observer.watch(sync.shared_chats.values())
                    job=post("claim",{"ticket":ticket,"held":held,"live":list(sync.shared_chats),"agent_roles":True})
                    if not job:
                        time.sleep(1)
                        continue
                    folder=memory_dir(args.spool,job);marker=folder/"binding.json"
                    tid=sync.shared_chats.get(job["chat"])
                    if not tid and control.client:
                        bind=json.loads(marker.read_text("utf8")) if marker.is_file() else None
                        try:
                            if bind and bind.get("head")==job["parent"]:
                                path=Path(bind["path"]) if bind.get("path") else rollout(bind["thread"])
                                if path and path.is_file() and file_digest(path)==bind.get("digest"):
                                    view=control.client.call("thread/resume",{"threadId":bind["thread"],"sandbox":"read-only","excludeTurns":True,**thread_options(job)})
                                    tid=view["thread"]["id"]
                            elif not bind and job["parent"] is None:
                                cwd=folder/"work";cwd.mkdir(parents=True,exist_ok=True)
                                view=control.client.call("thread/start",{"cwd":str(cwd),"sandbox":"read-only","approvalPolicy":"never",**thread_options(job)})
                                tid=view["thread"]["id"]
                                save(marker,{"thread":tid,"chat":job["chat"],"instance":job["instance"],"head":None,"cwd":str(cwd)})
                        except (OSError,shared_cli.RpcError):tid=None
                    if tid:
                        post("native-bind",{"thread":tid,"chat":job["chat"]})
                        observer.watch(set(sync.shared_chats.values())|{tid})
                        state.update(stage="live_prepare",job=job["id"],submission=live_submission(job),thread=tid)
                        save(active,state)
                        continue
                    if marker.is_file():
                        binding=json.loads(marker.read_text("utf-8"))
                        path=Path(binding["path"]) if binding.get("path") else rollout(binding["thread"])
                        if path and not path.exists() and native_sync.source_for(path).is_file():
                            native_sync.materialize(path)
                        if path and path.is_file() and "offset" in binding and path.stat().st_size!=binding["offset"]:
                            state.update(stage="defer",job=job["id"],chat=job["chat"])
                            save(active,state)
                            continue
                    state.update(stage="executing",job=job["id"])
                    save(active,state)
                    def emit(k,label):
                        try:
                            post("event",{"ticket":ticket,"job":job["id"],"key":k,"label":label})
                        except OSError:
                            pass
                    try:
                        folder=memory_dir(args.spool,job)
                        def load_history():
                            return post("history",{"ticket":ticket,"job":job["id"]})["turns"]
                        status,answer,binding=perform(exe,job,emit,folder,load_history,args.timeout)
                    except (OSError,ValueError):
                        status,answer,binding="failed","工作站无法取得 Codex 输出，请检查工作站的 CLI、历史目录和登录状态。",None
                    if status in ("waiting_writer","sync_changed"):
                        emit("native.writer" if status=="waiting_writer" else "native.sync",answer)
                        state.update(stage="defer",chat=job["chat"])
                        save(active,state)
                        continue
                    state.update(stage="report",status=status,answer=answer,binding=binding)
                    save(active,state)
                elif state["stage"]=="live_prepare":
                    if state["thread"] not in control.loaded():
                        state.update(stage="defer",chat=state["submission"]["chat"])
                        save(active,state)
                        continue
                    deadline=time.monotonic()+3
                    while state["thread"] not in observer.ready and time.monotonic()<deadline:time.sleep(.05)
                    post("live-intent",{"ticket":ticket,"job":state["job"],"thread":state["thread"]})
                    state.update(stage="live_sending",attempted=time.time())
                    save(active,state)
                    try:
                        control.enqueue(state["thread"],state["submission"])
                    except (OSError,shared_cli.RpcError):
                        control.reset()
                        continue
                    state["stage"]="live_accepted"
                    save(active,state)
                    continue
                elif state["stage"]=="live_sending":
                    # If the native receipt was lost, look up the exact client ID on both sides
                    # of dequeue. Never submit a second model turn on an ambiguous outcome.
                    seen=False
                    if state["thread"] in control.loaded():
                        try:seen=control.find(state["thread"],state["job"])
                        except (OSError,shared_cli.RpcError):control.reset()
                    if seen:state["stage"]="live_accepted"
                    elif time.time()-state["attempted"]<45:
                        time.sleep(1)
                        continue
                    else:state.update(stage="report",status="uncertain",answer="原生队列回执待核对，恢复可见记录后将自动同步。")
                    save(active,state)
                    continue
                elif state["stage"]=="live_accepted":
                    post("event",{"ticket":ticket,"job":state["job"],"key":"native.queued","label":"已进入当前原生终端队列"})
                    active.unlink()
                    continue
                elif state["stage"]=="defer":
                    post("defer",{"ticket":ticket,"job":state["job"]})
                    backoff[state["chat"]]=time.monotonic()+5
                    active.unlink()
                    continue
                elif state["stage"]=="executing":
                    state.update(stage="report",status="uncertain",answer="工作站在执行期间重启，运行结果待核实。")
                    save(active,state)
                report={k:state[k] for k in ("ticket","job","status","answer")}
                binding=state.get("binding")
                if binding and binding.get("turn"):
                    report.update(native_thread=binding["thread"],native_turn=binding["turn"])
                post("finish",report)
                if binding:
                    save(memory_dir(args.spool,binding)/"binding.json",binding)
                active.unlink()
            except HTTPError as e:
                print("Whitecat API HTTP",e.code,flush=True)
                time.sleep(4)
            except (URLError,ConnectionError,TimeoutError,sqlite3.OperationalError):
                time.sleep(2)


def options():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config",type=Path,help="UTF-8 JSON station settings")
    parser.add_argument("--server",default="http://127.0.0.1:18742")
    parser.add_argument("--key-file",type=Path,default=DEFAULT_STATE/"worker.key")
    parser.add_argument("--spool",type=Path,default=DEFAULT_STATE/"station")
    parser.add_argument("--name",default="Codex 工作站")
    parser.add_argument("--executor",default="default",help="Expected execution identity for this credential and spool")
    parser.add_argument("--no-native-discovery",dest="native_discovery",action="store_false",help="Only track chats already bound to this worker")
    parser.add_argument("--timeout",type=int,default=300)
    parser.add_argument("--native-roots",type=Path,nargs="*",default=[],help="Optional workspace roots for native TUI discovery; default: all interactive CLI workspaces")
    initial,_=parser.parse_known_args()
    if initial.config:
        config=json.loads(initial.config.read_text("utf-8-sig"))
        allowed={"server","key_file","spool","name","timeout","native_roots","native_discovery","executor"}
        if not isinstance(config,dict) or not set(config)<=allowed:parser.error("unknown config field")
        if "native_discovery" in config and type(config["native_discovery"]) is not bool:parser.error("native_discovery must be boolean")
        for key in ("spool","key_file"):
            if key in config:
                value=Path(config[key]).expanduser()
                config[key]=value if value.is_absolute() else initial.config.resolve().parent/value
        if "native_roots" in config:
            if not isinstance(config["native_roots"],list):parser.error("native_roots must be a list")
            config["native_roots"]=[initial.config.resolve().parent/Path(p).expanduser() for p in config["native_roots"]]
        parser.set_defaults(**config)
    args=parser.parse_args()
    if not args.name.strip() or len(args.name)>72 or not 15<=args.timeout<=3600:parser.error("invalid name or timeout")
    return args


if __name__=="__main__":
    run(options())
