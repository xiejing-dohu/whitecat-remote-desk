"""Operator CLI for the same single-user conversations as the Android app."""
import argparse
import json
import sys
import uuid
from http.client import HTTPConnection
from contextlib import closing

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--port",type=int,default=18742)
    subs=p.add_subparsers(dest="action",required=True)
    subs.add_parser("list")
    history=subs.add_parser("history")
    history.add_argument("chat",type=uuid.UUID)
    send=subs.add_parser("send",help="read a UTF-8 message from stdin")
    send.add_argument("--chat",type=uuid.UUID)
    send.add_argument("--command",type=uuid.UUID,required=True,help="keep this UUID when retrying")
    args=p.parse_args()
    path="/api/state"
    body=None
    if args.action=="history":path="/api/chats/"+str(args.chat)
    if args.action=="send":
        body=json.dumps({"chat":str(args.chat) if args.chat else None,"command":str(args.command),
                         "prompt":sys.stdin.buffer.read().decode("utf-8").strip(),"filename":"","sample":""}).encode()
        path="/api/jobs"
    with closing(HTTPConnection("127.0.0.1",args.port,timeout=20)) as c:
        c.request("POST" if body else "GET",path,body,{"X-Whitecat":"desk","Content-Type":"application/json"})
        r=c.getresponse()
        sys.stdout.buffer.write(r.read()+b"\n")
        if r.status>=300:raise SystemExit(1)

if __name__=="__main__":
    main()
