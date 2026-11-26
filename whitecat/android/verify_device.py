"""Install the release APK and use a separately signed-with-the-same-key instrumentation APK on a disposable emulator."""
import argparse
import concurrent.futures
import hashlib
import http.client
import json
import os
import re
import shlex
import shutil
import socket
import sqlite3
import struct
import subprocess
import sys
import tempfile
import threading
from contextlib import closing
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT.parent))
import mobile_gateway
from server import DB

def main():
    p=argparse.ArgumentParser()
    p.add_argument("--serial",required=True)
    p.add_argument("--adb",default=shutil.which("adb") or str(Path(os.getenv("LOCALAPPDATA",""))/"WhitecatAndroidTools/adb/platform-tools/adb.exe"))
    args=p.parse_args()
    def adb(*parts, timeout=120):
        out=subprocess.run([args.adb,"-s",args.serial,*parts],capture_output=True,timeout=timeout)
        if out.returncode:raise RuntimeError(out.stderr.decode(errors="replace")[:350])
        return out.stdout
    def shell(*parts):
        return adb("shell",shlex.join(parts),timeout=550).decode("utf-8",errors="replace").strip()
    if shell("getprop","ro.kernel.qemu")!="1":
        raise RuntimeError("This test changes connectivity; use a dedicated Android emulator")
    mobile_port=18744
    requests=[]
    receipts=[]
    class DropFirst(BaseHTTPRequestHandler):
        def log_message(self,*a):pass
        def route(self):
            data=self.rfile.read(int(self.headers.get("Content-Length",0))) or None
            headers={k:v for k,v in self.headers.items() if k.lower() in ("content-type","authorization")}
            with closing(http.client.HTTPConnection("127.0.0.1",mobile_port,timeout=25)) as upstream:
                upstream.request(self.command,self.path,data,headers)
                reply=upstream.getresponse();body=reply.read()
                if self.command=="POST" and self.path=="/api/jobs" and reply.status in (200,201):
                    requests.append(json.loads(data))
                    receipts.append((reply.status,json.loads(body)))
                    if len(receipts)==1:
                        self.connection.setsockopt(socket.SOL_SOCKET,socket.SO_LINGER,struct.pack("hh" if os.name=="nt" else "ii",1,0))
                        self.close_connection=True
                        return
                self.send_response(reply.status);self.send_header("Content-Length",str(len(body)))
                self.send_header("Content-Type",reply.getheader("Content-Type","application/json"));self.end_headers();self.wfile.write(body)
        do_GET=do_POST=route
    proxy=ThreadingHTTPServer(("127.0.0.1",0),DropFirst)
    threading.Thread(target=proxy.serve_forever,daemon=True).start()
    dest="tcp:"+str(mobile_port)
    def instrument(stage,**values):
        command=["am","instrument","-w","-e","stage",stage]
        for k,v in values.items():command+=["-e",k,v]
        command.append("dev.whitecat.check/.DeviceCheck")
        result=shell(*command)
        if "PASS "+stage not in result:
            # Instrumentation crash output can include launch extras; extract only our own diagnostic.
            message=next((line for line in result.splitlines() if "whitecat=FAIL " in line),"Instrumentation did not finish")
            raise RuntimeError(stage+" "+message)
        print("Android UI PASS",stage,flush=True)
    pkg="dev.whitecat.desk"
    wifi=shell("settings","get","global","wifi_on")
    data=shell("settings","get","global","mobile_data")
    try:
        # adb can mishandle non-ASCII paths on a Windows host.
        with tempfile.TemporaryDirectory(prefix="whitecat-install-") as tmp:
            for apk,name in [(ROOT/"白猫.apk","app.apk"),(ROOT/"test/whitecat-test.apk","test.apk")]:
                staged=Path(tmp)/name;shutil.copyfile(apk,staged)
                assert b"Success" in adb("install","-r",str(staged))
        print("APK installed on",args.serial,flush=True)
        adb("reverse",dest,"tcp:"+str(proxy.server_port))
        instrument("send",endpoint="http://127.0.0.1:18744",code=mobile_gateway.pairing_code(),
                   prompt="请计算 27、14、32 的总和，只回复“合计=数字”，使用半角等号。")
        assert len(receipts)==1 and receipts[0][0]==201,receipts
        adb("reverse","--remove",dest)
        shell("svc","wifi","disable");shell("svc","data","disable")
        shell("am","force-stop",pkg)
        instrument("offline")
        assert len(receipts)==1
        shell("am","force-stop",pkg)
        if wifi=="1":shell("svc","wifi","enable")
        if data=="1":shell("svc","data","enable")
        adb("reverse",dest,"tcp:"+str(proxy.server_port))
        instrument("resume",answer="合计=73",follow="把上一轮合计乘以 2 再加 5，只回复“复核=数字”，使用半角等号。",final="复核=151")
        assert len(receipts)==3 and receipts[0][1]==receipts[1][1] and receipts[1][0]==200,receipts
        assert requests[0]==requests[1] and requests[2]["command"]!=requests[0]["command"]
        chat=receipts[0][1]["chat"]
        assert receipts[2][1]["chat"]==chat
        with closing(sqlite3.connect(f"file:{DB.as_posix()}?mode=ro",uri=True)) as db:
            rows=db.execute("SELECT id,status,answer FROM jobs WHERE chat=? ORDER BY created",(chat,)).fetchall()
            assert len(rows)==2 and all(row[1]=="done" for row in rows)
            assert "合计=73" in rows[0][2] and "复核=151" in rows[1][2]
            counters=[]
            for job,_,_ in rows:
                counts=dict(db.execute("SELECT event_key,count(*) FROM events WHERE job=? GROUP BY event_key",(job,)))
                assert all(counts.get(k)==1 for k in ("accepted","claimed","spawn","finished")),counts
                counters.append({k:counts[k] for k in ("accepted","claimed","spawn","finished")})
        shell("am","start","-W","-n",pkg+"/.ChatActivity")
        import time
        time.sleep(2)
        shot=adb("exec-out","screencap","-p")
        assert shot.startswith(b"\x89PNG")
        (ROOT/"Android模拟器-验收.png").write_bytes(shot)
        evidence={"device":shell("getprop","ro.product.model"),"android":shell("getprop","ro.build.version.release"),
            "api":shell("getprop","ro.build.version.sdk"),"emulator":True,"package":pkg,
            "apk_sha256":hashlib.sha256((ROOT/"白猫.apk").read_bytes()).hexdigest(),"chat":chat,
            "fault":"reset after reading committed 201, remove ADB reverse, disable Wi-Fi and mobile data",
            "app_force_stops_before_recovery":2,"offline_retry_reached_backend":False,
            "statuses":[item[0] for item in receipts],"original_key_and_payload_preserved":True,
            "runs":[{"job":row[0],"answer":row[2],"events":counts} for row,counts in zip(rows,counters)]}
        (ROOT/"android-acceptance.json").write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
        print("PASS device-to-Codex; 201 reset -> offline -> 200 replay -> 201 follow-up",chat,flush=True)
    finally:
        proxy.shutdown();proxy.server_close()
        if wifi=="1":shell("svc","wifi","enable")
        if data=="1":shell("svc","data","enable")
        adb("reverse",dest,dest)

if __name__=="__main__":
    main()
