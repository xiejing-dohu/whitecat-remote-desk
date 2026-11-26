const $ = id => document.getElementById(id);
const statusNames = {queued:"等待工作站领取",running:"正在运行",done:"已完成",failed:"执行失败",uncertain:"运行结果待核实"};
const storage = "whitecat.v1.";
const linkedChat = new URLSearchParams(location.search).get("chat");
let selected = linkedChat || localStorage.getItem(storage+"chat") || "";
let pending = JSON.parse(localStorage.getItem(storage+"outbox") || "null");
let executor = pending?.executor || localStorage.getItem(storage+"executor") || "default";
let attachment = null, busy = false, running = false, revision = 0, previous = "", previousList = "";
const node = (tag, text, cls) => {
  const element = document.createElement(tag);
  if (text !== undefined) element.textContent = text;
  if (cls) element.className = cls;
  return element;
};
function cat() {
  const image = node("img"); image.src="/cat.png"; image.alt=""; return image;
}
async function api(path, body) {
  const response=await fetch(path,{method:body?"POST":"GET",headers:{"X-Whitecat":"desk",...(body?{"Content-Type":"application/json"}:{})},body:body?JSON.stringify(body):undefined});
  if(!response.ok) {
    const error=new Error((await response.json()).error || "请求失败"); error.status=response.status; throw error;
  }
  return response.json();
}
let executors=[], executorStamp="";
function selectedExecutorName(){
  return executors.find(item=>item.id===executor)?.name || "Codex";
}
function renderExecutors(items){
  const stamp=JSON.stringify(items.map(({id,name,online})=>[id,name,!!online]));
  executors=items;
  if(stamp!==executorStamp){
    executorStamp=stamp;
    const select=$("executor");select.replaceChildren();$("executor-rows").replaceChildren();
    for(const item of items){
      const option=node("option",item.name);option.value=item.id;select.append(option);
      const row=node("li");
      row.append(node("b",item.name),node("span",item.online?"在线":"离线","executor-presence"));
      $("executor-rows").append(row);
    }
  }
  $("executor").value=executor;
  $("executor-label").textContent=selectedExecutorName();
}
$("executor").onchange=()=>{
  if(busy||pending){$("executor").value=executor;return;}
  saveDraft();executor=$("executor").value;localStorage.setItem(storage+"executor",executor);select("");
};
$("manage-executors").onclick=()=>{
  $("agent-admin").showModal();refresh();
};
$("admin-close").onclick=()=>$("agent-admin").close();
$("agent-form").onsubmit=async event=>{
  event.preventDefault();const save=$("agent-save");if(save.disabled)return;
  const id=$("agent-id").value.trim(),name=$("agent-name").value.trim();
  save.disabled=true;$("config-path").hidden=true;$("admin-result").textContent="正在生成接入配置…";
  try{
    const result=await api("/api/admin/executors",{id,name});
    $("admin-result").textContent="配置已生成。使用这个配置文件启动工作进程，Agent 上线后即可领取任务。";
    $("config-path").textContent=result.config_path;$("config-path").hidden=false;
    $("agent-form").reset();await refresh();
  }catch(error){$("admin-result").textContent=error.message;}
  finally{save.disabled=false;}
};

function drawer(show) {
  document.body.classList.toggle("drawer",show);
  $("menu").setAttribute("aria-expanded",String(show));
  if(show) $("close").focus();
}
function updateControls() {
  $("send").disabled=busy || !!pending || running || !$("prompt").value.trim();
  $("prompt").disabled=!!pending;
  $("executor").disabled=busy||!!pending;
  $("attach").disabled=!!pending || busy;
  $("remove").disabled=!!pending || busy;
  $("retry").hidden=!pending || busy;
}
function draftKey(chat=selected,owner=executor) {
  return storage+"draft."+(chat || (owner==="default"?"new":"new."+owner));
}
function restoreDraft() {
  const draft=JSON.parse(localStorage.getItem(draftKey()) || "{}");
  const data=pending && (pending.chat || "")===selected ? pending : draft;
  $("prompt").value=data.prompt || "";
  attachment=data.filename?{filename:data.filename,sample:data.sample}:null;
  $("attachment").hidden=!attachment; $("filename").textContent=attachment?.filename || "";
  updateControls();
}
function saveDraft() {
  localStorage.setItem(draftKey(),JSON.stringify({prompt:$("prompt").value,...attachment}));
  updateControls();
}
function select(chat) {
  if(channel?.id!==chat)subscribe("");
  selected=chat; history.replaceState(null,"",chat?"?chat="+encodeURIComponent(chat):location.pathname); localStorage.setItem(storage+"chat",chat); revision++; previous=""; previousList="";
  running=false; $("content").replaceChildren(); $("title").textContent=chat?"会话":"新建会话";
  restoreDraft(); drawer(false); refresh();
}
function renderList(chats) {
  const stamp=JSON.stringify([selected,chats]);
  if(stamp===previousList) return;
  previousList=stamp; $("chats").replaceChildren();
  if(!chats.length) $("chats").append(node("p","暂无会话","muted"));
  for(const chat of chats) {
    const button=node("button",undefined,"chatrow"); button.setAttribute("aria-current",String(chat.chat===selected));
    button.append(node("span","◌"),node("span",chat.title)); button.onclick=()=>{if(!busy&&!pending)select(chat.chat);}; $("chats").append(button);
  }
}
const feedRows=new Map();
function showPart(slot,part,hasAnswer) {
  let view=slot.nodes.get(part.item);
  const words=["answer","commentary"].includes(part.kind);
  if(!view){
    view=node(words?"div":"details",undefined,words?"reply":"activity");
    if(!words)view.append(node("summary"),node("small"));
    slot.nodes.set(part.item,view);slot.area.append(view);
  }
  if(words){
    view.hidden=part.kind==="answer"&&hasAnswer;
    if(view.textContent!==part.body)view.textContent=part.body;
  }else{
    const tool={shell:"运行命令",mcp:"调用 MCP 工具",web:"搜索网页",files:"更新文件"}[part.kind]||"执行工具";
    const status={running:"◉ 执行中",done:"✓ 已完成",failed:"× 失败",interrupted:"已中断"}[part.status];
    const timing=part.started_ms>0&&part.ended_ms>=part.started_ms?" · "+((part.ended_ms-part.started_ms)/1000).toFixed(1)+" 秒":"";
    view.firstChild.textContent=(part.label||tool)+" · "+status+timing;
    view.lastChild.textContent=part.progress?"已收到 "+part.progress+" 次工具进度更新":tool+" · 原生 Codex CLI";
  }
}
let channel=null;
function subscribe(id){
  if(channel?.id===id)return;
  channel?.stop.abort();channel=null;if(!id)return;
  const ch={id,stop:new AbortController(),cursor:null,turns:new Map()};channel=ch;
  async function listen(){
    while(!ch.stop.signal.aborted){
      try{
        const headers={"X-Whitecat":"desk",Accept:"text/event-stream"};
        if(ch.cursor!==null)headers["Last-Event-ID"]=String(ch.cursor);
        const res=await fetch("/api/chats/"+id+"/stream",{headers,signal:ch.stop.signal,cache:"no-store"});
        if(!res.ok)throw Error("工作站暂时无法连接");
        const reader=res.body.getReader(),decoder=new TextDecoder();let buffer="";
        while(true){
          const {value,done}=await reader.read();if(done)break;
          buffer+=decoder.decode(value,{stream:true});let at;
          while((at=buffer.indexOf("\n\n"))!==-1){
            const lines=buffer.slice(0,at).split("\n");buffer=buffer.slice(at+2);
            if(channel!==ch)return;
            const event=lines.find(l=>l.startsWith("event: "))?.slice(7);
            const line=lines.find(l=>l.startsWith("data: "));if(!line)continue;
            const d=JSON.parse(line.slice(6));
            document.body.classList.toggle("online",d.online);
            document.querySelectorAll(".presence").forEach(e=>e.textContent=d.online?"工作站在线 · 实时同步":"工作站离线");
            if(event==="unavailable"){
              $("alert").textContent=d.code==="chat_deleted"?"此会话已在服务器删除。":"此会话暂不可用。";
              running=true;updateControls();ch.stop.abort();return;
            }
            if(event==="presence")continue;
            if(event==="snapshot")ch.turns.clear();
            for(const t of d.turns)ch.turns.set(t.id,t);
            const turns=[...ch.turns.values()].sort((a,b)=>a.created-b.created);
            if(d.turns.length||event==="snapshot")renderTurns(turns);
            const scroll=$("scroll"),atBottom=scroll.scrollHeight-scroll.scrollTop-scroll.clientHeight<70;
            for(const p of d.parts||[]){
              const t=ch.turns.get(p.job);if(!t)continue;
              const parts=t.parts||(t.parts=[]),idx=parts.findIndex(x=>x.item===p.item);
              if(idx===-1)parts.push(p);else parts[idx]=p;
              if(feedRows.has(t.id))showPart(feedRows.get(t.id),p,!!t.answer);
            }
            if(atBottom&&d.parts?.length)scroll.scrollTop=scroll.scrollHeight;
            ch.cursor=d.stream_cursor;$("title").textContent=d.title;
            running=!d.native_thread&&["queued","running"].includes(turns.at(-1)?.status);updateControls();
          }
        }
      }catch(e){if(ch.stop.signal.aborted)return;}
      if(channel===ch)document.querySelectorAll(".presence").forEach(e=>e.textContent="已断线 · 正在重连");
      await new Promise(resolve=>setTimeout(resolve,800));
    }
  }
  listen();
}
function renderTurns(turns) {
  const stamp=JSON.stringify([selected,turns]);
  if(stamp===previous) return;
  const initial=!previous, scroll=$("scroll"), atBottom=scroll.scrollHeight-scroll.scrollTop-scroll.clientHeight<70;
  const expanded=new Set([...document.querySelectorAll("details[open]")].map(d=>d.dataset.job));
  previous=stamp; const root=$("content"); root.replaceChildren();feedRows.clear();
  if(!turns.length) {
    const empty=node("section",undefined,"empty"); empty.append(cat(),node("h1","想让 "+selectedExecutorName()+" 做些什么？"),node("p","从这里向你的工作站派发任务，也可以附上一份文本文件。"));
    const example=node("button","试试汇总一份文件  ↗","suggest"); example.onclick=()=>{$("prompt").value="请汇总附件中的数据，列出条目数和合计。";saveDraft();$("file").click();};
    empty.append(example); root.append(empty); return;
  }
  for(const turn of turns) {
    const article=node("article",undefined,"turn"), user=node("div",turn.prompt,"user");
    if(turn.filename) user.append(node("small","附件 · "+turn.filename,"filetag"));
    const name=node("div",undefined,"name"); name.append(cat(),node("span",selectedExecutorName()));
    const activity=node("details",undefined,"activity"); activity.dataset.job=turn.id; activity.open=expanded.has(turn.id);
    activity.append(node("summary",statusNames[turn.status]+" · "+turn.events.length+" 条活动"));
    const list=node("ol"); for(const evt of turn.events) {
      const li=node("li"); li.append(node("time",new Date(evt.at*1000).toLocaleTimeString("zh-CN",{hour12:false})),document.createTextNode(evt.label)); list.append(li);
    }
    activity.append(list); article.append(user);
    for(const prompt of turn.followups||[]) article.append(node("div",prompt,"user"));
    article.append(name,activity);
    const slot={area:node("div"),nodes:new Map()};feedRows.set(turn.id,slot);article.append(slot.area);
    for(const p of turn.parts||[])showPart(slot,p,!!turn.answer);
    if(turn.answer) article.append(node("div",turn.answer,"reply"));
    if(turn.status==="done") {
      const download=node("button","下载回复 .txt","download");
      download.onclick=async()=>{try{
        const res=await fetch("/api/results/"+turn.id,{headers:{"X-Whitecat":"desk"}});
        if(!res.ok) throw new Error("下载失败");
        const url=URL.createObjectURL(await res.blob()), a=node("a"); a.href=url; a.download="whitecat-reply.txt"; a.click(); setTimeout(()=>URL.revokeObjectURL(url),3000);
      }catch(e){$("alert").textContent=e.message;}};
      article.append(download);
    }
    root.append(article);
  }
  if(initial || atBottom) scroll.scrollTop=scroll.scrollHeight;
}
async function refresh() {
  const token=++revision;
  try {
    const state=await api("/api/state");
    if(token!==revision) return;
    document.body.classList.toggle("online",state.online);
    document.querySelectorAll(".presence").forEach(e=>e.textContent=state.online?"工作站在线":"工作站离线");
    const current=state.chats.find(c=>c.chat===selected);
    if(current)executor=current.executor||"default";
    const catalog=state.executors || [{id:"default",name:"Codex",online:state.online}];
    if(!catalog.some(item=>item.id===executor)&&!pending)executor="default";
    localStorage.setItem(storage+"executor",executor);
    renderExecutors(catalog);renderList(state.chats.filter(chat=>(chat.executor||"default")===executor));
    const agentOnline=catalog.find(item=>item.id===executor)?.online;
    document.body.classList.toggle("online",!!agentOnline);
    document.querySelectorAll(".presence").forEach(el=>el.textContent=agentOnline?"Agent 在线":"Agent 离线");
    if(selected && !current) { select(""); return; }
    subscribe(selected);
    if(selected)return;
    const detail={};
    const turns=detail.turns||[];
    if(token!==revision) return;
    $("title").textContent=current?.title || "新建会话";
    running=!detail.native_thread && ["queued","running"].includes(turns.at(-1)?.status);
    if(!pending && $("alert").dataset.offline) {$("alert").textContent=""; delete $("alert").dataset.offline;}
    renderTurns(turns); updateControls();
  } catch(e) {
    if(token!==revision) return;
    document.body.classList.remove("online"); document.querySelectorAll(".presence").forEach(el=>el.textContent="连接中断");
    if(!pending){$("alert").textContent="正在重新连接工作台…";$("alert").dataset.offline="1";}
  }
}
async function deliver() {
  if(busy || !pending) return;
  busy=true; updateControls(); $("alert").textContent="正在提交…";
  try{
    const data=await api("/api/jobs",pending);
    localStorage.removeItem(draftKey(pending.chat,pending.executor||executor));
    localStorage.removeItem(storage+"outbox"); pending=null;
    $("alert").textContent=""; select(data.chat);
  }catch(e){
    if(e.status && e.status>=400 && e.status<500) {
      localStorage.removeItem(storage+"outbox"); pending=null; $("alert").textContent=e.message;
    }else{
      $("alert").textContent="未收到回执。请用原请求重试。";
    }
  }finally{busy=false;updateControls();}
}
$("composer").onsubmit=e=>{
  e.preventDefault(); if($("send").disabled) return;
  pending={command:crypto.randomUUID(),chat:selected || null,prompt:$("prompt").value.trim(),filename:attachment?.filename || "",sample:attachment?.sample || ""};
  if(!selected)pending.executor=executor;
  localStorage.setItem(storage+"outbox",JSON.stringify(pending)); deliver();
};
$("prompt").oninput=saveDraft;
$("prompt").onkeydown=e=>{if(e.key==="Enter" && (e.ctrlKey||e.metaKey) && !e.isComposing){e.preventDefault();$("composer").requestSubmit();}};
$("retry").onclick=deliver;
$("new").onclick=()=>{if(!busy&&!pending)select("");};
$("menu").onclick=()=>drawer(true);
$("close").onclick=$("shade").onclick=()=>drawer(false);
document.addEventListener("keydown",e=>{if(e.key==="Escape"){drawer(false);$("menu").focus();}});
$("attach").onclick=()=>$("file").click();
$("file").onchange=async()=>{
  const file=$("file").files[0]; if(!file) return;
  const target=selected; $("file").value="";
  try{
    if(file.size>32768) throw new Error("请选择 32 KB 以内的 UTF-8 文本文件");
    const text=new TextDecoder("utf-8",{fatal:true}).decode(await file.arrayBuffer());
    if(target!==selected) return;
    attachment={filename:file.name,sample:text}; $("attachment").hidden=false;
    $("filename").textContent=file.name; $("alert").textContent=""; saveDraft();
  }catch(e){$("alert").textContent="请选择 32 KB 以内的 UTF-8 文本文件";}
};
$("remove").onclick=()=>{attachment=null;$("attachment").hidden=true;saveDraft();};
restoreDraft(); if(pending) $("alert").textContent="上一条发送尚未确认，可以按原请求重试。";
refresh();
(async function poll(){await new Promise(r=>setTimeout(r,1400)); if(!document.hidden&&(!selected||$("agent-admin").open||$("menu").getAttribute("aria-expanded")==="true")) await refresh(); poll();})();
document.addEventListener("visibilitychange",()=>{if(!document.hidden) refresh();});
