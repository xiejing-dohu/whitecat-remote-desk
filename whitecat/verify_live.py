"""Live browser/CLI acceptance. Creates one test chat and one follow-up on a running Whitecat desk."""
import argparse
import concurrent.futures
import hashlib
import http.client
import json
import socket
import time
from pathlib import Path
from urllib.parse import urlsplit
from playwright.sync_api import sync_playwright

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--url",default="http://127.0.0.1:18742")
    parser.add_argument("--evidence",type=Path,required=True)
    args=parser.parse_args()
    base=args.url
    with sync_playwright() as p:
        browser=p.chromium.launch(channel="msedge")
        page=browser.new_page(viewport={"width":390,"height":844},accept_downloads=True)
        errors=[]
        page.on("pageerror",lambda e:errors.append(str(e)))
        page.goto(base)
        page.get_by_text("工作站在线").last.wait_for()
        page.locator("#file").set_input_files({"name":"样例.txt","mimeType":"text/plain","buffer":b"18\n47\n32\n"})
        page.locator("#prompt").fill("请统计附件中数字的条数和总和，只回复 rows=条数 total=总和，用阿拉伯数字填写。")
        received={}
        def lose_receipt(route):
            # Forward the browser payload to the real server. Observe HTTP headers after COMMIT,
            # then close the upstream socket and reset the browser request without returning a receipt.
            target=urlsplit(base)
            conn=http.client.HTTPConnection(target.hostname,target.port,timeout=10)
            received["body"]=route.request.post_data_json
            conn.request("POST","/api/jobs",route.request.post_data_buffer,headers={"Content-Type":"application/json","X-Whitecat":"desk"})
            response=conn.getresponse()
            received["initial_status"]=response.status
            assert response.status==201
            conn.close()
            route.abort("connectionreset")
        page.route("**/api/jobs",lose_receipt)
        page.get_by_role("button",name="发送任务").click()
        page.get_by_role("button",name="按原请求重试").wait_for()
        page.unroute("**/api/jobs")
        page.reload()
        with page.expect_response(lambda r:r.url==base+"/api/jobs" and r.request.method=="POST") as receipt:
            page.get_by_role("button",name="按原请求重试").click()
        assert receipt.value.status==200
        ids=receipt.value.json()
        head={"X-Whitecat":"desk"}
        def repeat(body):
            target=urlsplit(base)
            conn=http.client.HTTPConnection(target.hostname,target.port,timeout=10)
            conn.request("POST","/api/jobs",json.dumps(body).encode(),headers={**head,"Content-Type":"application/json"})
            response=conn.getresponse()
            code,data=response.status,json.load(response)
            conn.close()
            return code,data
        with concurrent.futures.ThreadPoolExecutor(2) as pool:
            replays=list(pool.map(repeat,[received["body"]]*2))
        assert all(status==200 and result==ids for status,result in replays)
        assert repeat({**received["body"],"prompt":"不同内容"})[0]==409
        print("Committed before response reset; browser reload + original key + 2 concurrent replays:",ids,flush=True)
        def await_turn(index):
            end=time.monotonic()+350
            while time.monotonic()<end:
                turns=page.request.get(base+"/api/chats/"+ids["chat"],headers=head).json()["turns"]
                if len(turns)>index and turns[index]["status"] not in ("queued","running"):
                    turn=turns[index]
                    assert turn["status"]=="done",(turn["status"],turn["answer"],turn["events"])
                    return turns
                page.wait_for_timeout(1200)
            raise AssertionError("Codex execution timed out")
        first=await_turn(0)
        assert len(first)==1 and first[0]["id"]==ids["job"]
        reply="".join(first[0]["answer"].lower().split())
        assert "rows=3" in reply and "total=97" in reply,first[0]["answer"]
        labels=[e["label"] for e in first[0]["events"]]
        assert labels.count("Codex 工作站已领取")==labels.count("已启动 Codex CLI")==1
        page.get_by_role("button",name="下载回复 .txt").wait_for()
        with page.expect_download() as downloaded:
            page.get_by_role("button",name="下载回复 .txt").click()
        content=Path(downloaded.value.path()).read_bytes()
        assert content.decode()==first[0]["answer"]
        page.locator(".activity summary").click()
        assert page.get_by_text("已启动 Codex CLI",exact=False).last.is_visible()
        for width in (360,390,1280):
            page.set_viewport_size({"width":width,"height":844})
            assert page.evaluate("document.documentElement.scrollWidth<=innerWidth")
        page.set_viewport_size({"width":390,"height":844})
        page.locator("#prompt").fill("将刚才的总和加上 8，只回复新的数字。")
        page.get_by_role("button",name="发送任务").click()
        turns=await_turn(1)
        assert len(turns)==2 and "105" in turns[1]["answer"],turns[1]["answer"]
        page.get_by_role("button",name="打开会话列表").click()
        assert page.locator("#chats button[aria-current=true]").count()==1
        assert page.request.post(base+"/worker/claim",data={}).status==403
        assert page.request.post(base+"/api/jobs",headers={**head,"Origin":"http://other.invalid"},data={}).status==403
        assert not errors,errors
        summary={"chat":ids["chat"],"first_job":ids["job"],"first_answer":first[0]["answer"],"claim_count":labels.count("Codex 工作站已领取"),
                 "cli_starts":labels.count("已启动 Codex CLI"),"initial_status":received["initial_status"],
                 "browser_failure":"connectionreset","reload_replay":200,"parallel_replays":len(replays),"changed_payload":409,
                 "result_sha256":hashlib.sha256(content).hexdigest(),"follow_up_job":turns[1]["id"],"follow_up_answer":turns[1]["answer"],
                 "widths":[360,390,1280],"unsigned_worker":403,"cross_origin":403}
        args.evidence.write_text(json.dumps(summary,indent=2,ensure_ascii=False)+"\n",encoding="utf-8")
        print("PASS",json.dumps(summary,ensure_ascii=False),flush=True)
        browser.close()

if __name__=="__main__":
    main()
