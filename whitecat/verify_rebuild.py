"""Exercise legacy history recovery with nine synthetic prior turns and one real Codex invocation."""
import json
import os
import secrets
import sqlite3
import tempfile
import time
import uuid
from contextlib import closing
from pathlib import Path


def main():
    with tempfile.TemporaryDirectory(prefix="whitecat-legacy-check-") as tmp:
        os.environ["WHITECAT_STATE"]=tmp
        import server
        import worker
        server.init()
        value="WC-"+secrets.token_hex(4).upper()
        def ident():return str(uuid.uuid4())
        chat=ident()
        with closing(sqlite3.connect(server.DB)) as db,db:
            db.row_factory=sqlite3.Row
            def seed(room,status,prompt,answer,i):
                key=ident()
                db.execute("""INSERT INTO jobs(id,chat,command,digest,prompt,filename,sample,created,status,answer)
                              VALUES(?,?,?,?,?,'','',?,?,?)""",(key,room,ident(),ident(),prompt,time.time()-300+i,status,answer))
                return key
            for idx in range(9):
                question=("请记下最早的标记："+value) if idx==0 else "已确认第 "+str(idx+1)+" 项。"
                last=seed(chat,"done",question,"已记录",idx)
            seed(ident(),"done","其他会话","不得合并",11)
            seed(chat,"failed","失败轮","不得合并",12)
            _,receipt=server.submit(db,{"chat":chat,"command":ident(),"prompt":"只回复最早记下的 WC- 标记，不要增加标点。"})
            ticket=ident()
            job=server.claim(db,ticket)
            assert job["parent"]==last and job["id"]==receipt["job"] and len(job["history"])==6
            def complete_history():
                history=server.worker(db,"/worker/history",{"ticket":ticket,"job":job["id"]})["turns"]
                assert len(history)==9 and history[-1]["id"]==last and value in history[0]["prompt"]
                return history
            folder=worker.memory_dir(Path(tmp)/"station",job)
            folder.mkdir(parents=True)
            worker.save(folder/"binding.json",{"head":last,"chat":chat,"instance":job["instance"],"thread":ident(),"digest":"missing"})
            events=[]
            status,answer,binding=worker.perform(worker.codex_command(),job,lambda key,label:events.append(key),folder,complete_history,180)
            assert status=="done" and answer.strip()==value and "memory.rebuild" in events
            assert binding["head"]==job["id"] and binding["digest"]
            evidence={"seeded_completed_turns":9,"other_chat_excluded":True,"failed_turn_excluded":True,"missing_native_history":True,
                      "response_recovered_first_marker":True,"codex_starts":events.count("spawn"),"mode":"rebuild"}
            Path(__file__).with_name("history-recovery.json").write_text(json.dumps(evidence,indent=2)+"\n",encoding="utf-8")
            print("PASS nine-turn history reconstruction; native CLI recovered the first marker")

if __name__=="__main__":
    main()
