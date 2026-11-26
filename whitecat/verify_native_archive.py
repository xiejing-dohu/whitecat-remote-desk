"""Regression for compressed Codex history with a completed turn after an orphaned open turn."""
import hashlib
import json
import tempfile
import time
import uuid
from pathlib import Path

import native_sync

try:
    from compression import zstd
except ImportError:
    import zstandard as zstd


def main():
    with tempfile.TemporaryDirectory() as tmp:
        base=Path(tmp)
        path=base/"rollout.jsonl"
        chat,thread,instance,orphan,done=[str(uuid.uuid4()) for _ in range(5)]
        rows=[]
        def add(kind,turn,**extra):
            event=dict(type=kind,turn_id=turn,**extra)
            rows.append(json.dumps({"type":"event_msg","payload":event}).encode()+b"\n")
        add("task_started",orphan)
        add("user_message",orphan,message="unfinished")
        add("task_started",done)
        add("user_message",done,message="hello")
        add("task_complete",done,last_agent_message="hi")
        data=b"".join(rows)
        archive=path.with_suffix(".jsonl.zst")
        archive.write_bytes(zstd.compress(data))
        jobs={}
        def post(action,body):
            assert action=="native-import"
            jobs.setdefault(body["turn"],str(uuid.uuid4()))
            return {"head":jobs[body["turn"]]}
        sync=native_sync.NativeSync(base,post)
        sync.state={"links":{}}
        folder=base/chat
        def scan():return sync.scan(folder,chat,thread,path,str(base),instance)
        assert not scan() and list(jobs)==[done] and not path.exists()
        marker=json.loads((folder/"binding.json").read_text("utf8"))
        assert marker["offset"]==len(data) and marker["digest"]==hashlib.sha256(data).hexdigest()
        native_sync.materialize(path)
        native_sync.materialize(path)
        assert path.read_bytes()==data and archive.exists()
        assert scan()
        signature=sync.stable_open[chat][0]
        sync.stable_open[chat]=(signature,time.monotonic()-1)
        assert not scan() and list(jobs)==[done]
        path.write_bytes(data+b"{")
        assert scan()
        add("task_complete",orphan,last_agent_message="finished")
        path.write_bytes(b"".join(rows))
        assert not scan() and set(jobs)=={done,orphan}
    print("PASS compressed history, orphan recovery, duplicate replay, atomic restore and partial-line hold")


if __name__=="__main__":main()
