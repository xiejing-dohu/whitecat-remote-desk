"""Exercise cold launch, durable appearance and both setup tutorials on the existing emulator."""
import argparse
import base64
import json
import os
from pathlib import Path
import subprocess

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("phase",choices=["choose","resume","remote_deletion","server_profiles","executor_hierarchy","live_directory"])
    parser.add_argument("--serial",default="127.0.0.1:5581")
    parser.add_argument("--out",type=Path,required=True)
    args=parser.parse_args()
    args.out.mkdir(parents=True,exist_ok=True)
    adb=Path(os.environ["LOCALAPPDATA"])/"WhitecatAndroidTools/adb/platform-tools/adb.exe"
    test_state=args.out/"appearance.json"
    state=json.loads(test_state.read_text("utf8")) if test_state.exists() else {}
    def device(*cmd,timeout=75):
        return subprocess.check_output([str(adb),"-s",args.serial,*cmd],timeout=timeout)
    assert device("shell","getprop","ro.kernel.qemu").strip()==b"1"
    device("shell","am","force-stop","dev.whitecat.desk")
    stage="personal_"+args.phase if args.phase in ("choose","resume") else args.phase
    options=["-e","stage",stage]
    if args.phase=="resume":
        for key in ("paper","font_px"):options.extend(["-e",key,state[key]])
    lines=device("shell","am","instrument","-w",*options,"dev.whitecat.check/.DeviceCheck",timeout=115).decode("utf8","replace").splitlines()
    blocks={}
    for line in lines:
        if line.startswith("INSTRUMENTATION_STATUS: design_capture="):
            name,pos,data=line.partition("=")[2].split("|",2)
            if name in {"21-welcome.png","21-appearance.png","21-local.png","21-remote.png","21-remote-deleted.png","22-storage.png","22-settings.png","22-executors.png","22-server-profiles.png","22-workstation.png","23-home.png"}:
                blocks.setdefault(name,{})[int(pos)]=data
    for name,parts in blocks.items():
        (args.out/name).write_bytes(base64.b64decode("".join(parts[p] for p in sorted(parts)),validate=True))
    result={k:v for line in lines if line.startswith("INSTRUMENTATION_RESULT: ") for k,_,v in [line[len("INSTRUMENTATION_RESULT: "):].partition("=")]}
    if result.get("whitecat")!="PASS "+stage:
        raise RuntimeError(result.get("whitecat",result.get("shortMsg","Activity did not complete")))
    for key in ("paper","font_px","default_font_px","negative_cases","migration"):
        if key in result:state[key]=result[key]
    state[args.phase]=True
    test_state.write_text(json.dumps(state,indent=2)+"\n",encoding="utf8")
    print("PASS",stage,state)
if __name__=="__main__": main()
