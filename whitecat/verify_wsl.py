"""Windows to WSL2 integration test. Requires Ubuntu, uv's Playwright environment and Edge."""
import json
import os
import secrets
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parent
PORT = 18743
LINUX_SERVER = """
import os, pathlib, sys, tempfile, threading
sys.path.insert(0, os.getcwd())
with tempfile.TemporaryDirectory(prefix='whitecat-wsl-') as root:
    os.environ['WHITECAT_STATE']=root
    key=pathlib.Path(root)/'worker.key'
    key.write_text(sys.stdin.readline().strip())
    key.chmod(0o600)
    import server
    api=server.ThreadingHTTPServer(('127.0.0.1', int(sys.argv[1])),server.Handler)
    api.worker_key=server.init()
    def stop_on_close():
        sys.stdin.read()
        api.shutdown()
    threading.Thread(target=stop_on_close,daemon=True).start()
    api.serve_forever()
    api.server_close()
"""

def main():
    base=f"http://127.0.0.1:{PORT}"
    with tempfile.TemporaryDirectory(prefix="whitecat-test-") as folder:
        tmp=Path(folder)
        key=secrets.token_urlsafe(40)
        keyfile=tmp/"worker.key"
        keyfile.write_text(key)
        with subprocess.Popen(["wsl.exe","-d","Ubuntu-22.04","--cd",str(ROOT),"-e","python3","-B","-c",LINUX_SERVER,str(PORT)],
                              stdin=subprocess.PIPE,cwd=ROOT) as linux:
            try:
                linux.stdin.write((key+"\n").encode())
                linux.stdin.flush()
                for _ in range(25):
                    if linux.poll() is not None:
                        raise RuntimeError("Linux API stopped")
                    try:
                        req=Request(base+"/worker/heartbeat",b"{}",headers={"Authorization":"Bearer "+key,"Content-Type":"application/json"})
                        with urlopen(req,timeout=2) as resp:
                            assert resp.status==200
                        break
                    except OSError:
                        time.sleep(1)
                else:
                    raise RuntimeError("WSL localhost forwarding is unavailable")
                proc=subprocess.Popen([sys.executable,"-B","worker.py","--server",base,"--key-file",str(keyfile),"--spool",str(tmp/"spool")],cwd=ROOT)
                try:
                    evidence=ROOT/"acceptance-wsl.json"
                    subprocess.run([sys.executable,"-B","-X","utf8","verify_live.py","--url",base,"--evidence",str(evidence)],cwd=ROOT,check=True)
                    record=json.loads(evidence.read_text("utf-8"))
                    record.update(server_os="WSL2 Ubuntu 22.04",worker_os="Windows",transport="TCP via WSL localhost forwarding")
                    evidence.write_text(json.dumps(record,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
                finally:
                    proc.terminate()
                    proc.wait(timeout=10)
            finally:
                linux.stdin.close()
                try:
                    linux.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    linux.terminate()

if __name__=="__main__":
    main()
