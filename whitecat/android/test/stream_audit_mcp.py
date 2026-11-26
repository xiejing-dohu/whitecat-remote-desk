"""Dedicated acceptance MCP. Its only input selects a fixed read-only check of daily.csv."""
import csv
import json
import sys
import time
from pathlib import Path

def send(packet):
    sys.stdout.buffer.write(json.dumps(dict(jsonrpc="2.0",**packet)).encode()+b"\n");sys.stdout.buffer.flush()

def main():
    for raw in sys.stdin.buffer:
        msg=json.loads(raw)
        if "id" not in msg:continue
        method=msg.get("method");params=msg.get("params",{})
        if method=="initialize":
            value={"protocolVersion":params.get("protocolVersion","2024-11-05"),"capabilities":{"tools":{}},
                   "serverInfo":{"name":"whitecat-audit-fixture","version":"1"}}
        elif method=="tools/list":
            value={"tools":[{"name":"audit_csv","annotations":{"readOnlyHint":True,"destructiveHint":False,"idempotentHint":True,"openWorldHint":False},"description":"Read the dedicated daily.csv. Run each check separately; ready performs a quick availability check.",
                   "inputSchema":{"type":"object","properties":{"stage":{"type":"string","enum":["ready","count","sum","check"]}},"required":["stage"],"additionalProperties":False}}]}
        elif method=="tools/call" and params.get("name")=="audit_csv":
            stage=params.get("arguments",{}).get("stage")
            with Path(sys.argv[1]).open(encoding="utf8",newline="") as f:rows=list(csv.DictReader(f))
            if stage not in ("ready","count","sum","check"):
                send({"id":msg["id"],"error":{"code":-32602,"message":"Unknown stage"}});continue
            if stage!="ready":
                token=params.get("_meta",{}).get("progressToken")
                for i in range(3):
                    if token is not None:send({"method":"notifications/progress","params":{"progressToken":token,"progress":i+1,"total":3,"message":"Checking batch"}})
                    time.sleep(3)
            result={"ready":"READY","count":"ROWS="+str(len(rows)),"sum":"TOTAL="+str(sum(int(r["units"]) for r in rows)),
                    "check":"ZERO="+str(sum(int(r["units"])==0 for r in rows))}[stage]
            value={"content":[{"type":"text","text":result}]}
        elif method=="ping":value={}
        else:send({"id":msg["id"],"error":{"code":-32601,"message":"Method unavailable"}});continue
        send({"id":msg["id"],"result":value})
if __name__=="__main__":main()
