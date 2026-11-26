package dev.whitecat.check;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;

public class DeviceCheck extends Instrumentation {
    Bundle options;
    volatile Activity activity;
    @Override public void callActivityOnResume(Activity next){
        super.callActivityOnResume(next);
        if(next.getClass().getName().equals("dev.whitecat.desk.ChatActivity"))activity=next;
    }
    @Override public void onCreate(Bundle arguments){options=arguments;start();}
    View view(String name){int id=getTargetContext().getResources().getIdentifier(name,"id","dev.whitecat.desk");return activity.findViewById(id);}
    String contents(View v){
        if(v==null||v.getVisibility()!=View.VISIBLE)return "";
        if(v instanceof TextView)return ((TextView)v).getText().toString()+"\n";
        StringBuilder all=new StringBuilder();if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)all.append(contents(g.getChildAt(i)));}return all.toString();
    }
    void await(String name,String expected,int seconds){
        long until=SystemClock.elapsedRealtime()+seconds*1000L;
        do{
            boolean[] yes={false};
            runOnMainSync(()->{View v=view(name);yes[0]=v!=null&&v.isShown()&&(expected.isEmpty()||contents(v).contains(expected));});
            if(yes[0])return;
            SystemClock.sleep(150);
        }while(SystemClock.elapsedRealtime()<until);
        throw new AssertionError("Timed out waiting for "+name+" / "+expected);
    }
    void fill(String name,String value){runOnMainSync(()->((EditText)view(name)).setText(value));}
    void press(String name){
        runOnMainSync(()->{View v=view(name);if(v==null||!v.isShown()||!v.isEnabled()||!v.performClick())throw new AssertionError("Unavailable "+name);});
    }
    org.json.JSONObject cache() throws Exception{
        SharedPreferences prefs=getTargetContext().getSharedPreferences("desk",Context.MODE_PRIVATE);
        String base=getTargetContext().getSharedPreferences("connection",Context.MODE_PRIVATE).getString("active","");
        if(!base.isEmpty()&&!base.equals(prefs.getString("base","http://127.0.0.1:18744")))
            prefs=getTargetContext().getSharedPreferences("desk."+java.util.UUID.nameUUIDFromBytes(base.getBytes("UTF-8")),Context.MODE_PRIVATE);
        String chat=prefs.getString("chat","");
        if(!chat.equals(options.getString("chat"))) throw new AssertionError("Unexpected selected chat");
        return new org.json.JSONObject(prefs.getString("cache."+chat,"{}"));
    }
    void verifyProjection(Bundle result) throws Exception{
        org.json.JSONObject local=cache();org.json.JSONArray turns=local.getJSONArray("turns");
        java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
        for(int j=0;j<turns.length();j++){org.json.JSONObject turn=turns.getJSONObject(j);
            for(String field:new String[]{"id","prompt","answer","status"}) digest.update((turn.getString(field)+"\n").getBytes("UTF-8"));}
        StringBuilder hex=new StringBuilder();for(byte part:digest.digest())hex.append(String.format(java.util.Locale.ROOT,"%02x",part&255));
        result.putString("projection",hex.toString());result.putString("revision",String.valueOf(local.getLong("revision")));
        result.putString("turns",String.valueOf(turns.length()));
    }
    void imageOfPage(String filename) throws Exception {
        waitForIdleSync();SystemClock.sleep(250);
        android.graphics.Bitmap shot=getUiAutomation().takeScreenshot();
        if(shot==null)throw new AssertionError("Missing device pixels");
        try(java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){
            if(!shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out))throw new AssertionError("Cannot encode capture");
            String png=android.util.Base64.encodeToString(out.toByteArray(),android.util.Base64.NO_WRAP);
            for(int at=0;at<png.length();at+=24000){
                Bundle part=new Bundle();part.putString("design_capture",filename+"|"+at+"|"+png.substring(at,Math.min(at+24000,png.length())));sendStatus(1,part);
            }
        } finally {shot.recycle();}
    }
    void openList(){
        if(view("search")==null)press(view("nav_list")!=null?"nav_list":"list_chats");
        await("search","",10);
    }
    void selectTaggedChat() {
        boolean[] found={false};
        runOnMainSync(()->{
            ViewGroup rows=(ViewGroup)view("list_rows");
            for(int i=0;i<rows.getChildCount();i++){
                View child=rows.getChildAt(i);
                if(options.getString("chat").equals(child.getTag())){found[0]=child.performClick();break;}
            }
        });
        if(!found[0])throw new AssertionError("Missing demo chat");
    }
    void collectToolRows(View v,java.util.List<View> rows){
        if(v.getContentDescription()!=null&&v.getContentDescription().toString().startsWith("audit_csv，已完成"))rows.add(v);
        if(v instanceof ViewGroup){ViewGroup group=(ViewGroup)v;for(int i=0;i<group.getChildCount();i++)collectToolRows(group.getChildAt(i),rows);}
    }
    void verifyDesign() throws Exception {
        openList();
        fill("search","no-match-whitecat-19");await("list_rows","没有匹配",15);
        fill("search","白猫流式验收18");await("list_rows","白猫流式验收18C",15);
        imageOfPage("whitecat19-list.png");selectTaggedChat();await("transcript","ROWS=42 TOTAL=126 ZERO=6",20);await("status","实时同步",20);
        java.util.List<View> tools=new java.util.ArrayList<>();runOnMainSync(()->collectToolRows(view("transcript"),tools));
        if(tools.size()<3)throw new AssertionError("Tool history unavailable");
        View target=tools.get(tools.size()-3);
        runOnMainSync(()->{
            target.performClick();ViewGroup transcript=(ViewGroup)view("transcript");
            ScrollView scroll=(ScrollView)transcript.getParent();android.graphics.Rect rect=new android.graphics.Rect();
            target.getDrawingRect(rect);scroll.offsetDescendantRectToMyCoords(target,rect);
            scroll.scrollTo(0,Math.max(0,rect.top-scroll.getHeight()/4));
        });
        await("transcript","调用 MCP 工具 · 原生 Codex CLI",5);
        boolean[] fits={false};runOnMainSync(()->{
            View window=activity.findViewById(android.R.id.content),composer=(View)view("message").getParent(),scroll=(View)view("transcript").getParent();
            android.graphics.Rect visible=new android.graphics.Rect();
            fits[0]=composer.getHeight()<window.getHeight()/3&&scroll.getHeight()>window.getHeight()/2&&target.getGlobalVisibleRect(visible)&&visible.height()>0;
        });
        if(!fits[0])throw new AssertionError("Composer or tool row obscures the transcript");
        imageOfPage("whitecat19-tools.png");
        runOnMainSync(()->target.performClick());
        runOnMainSync(()->((View)view("heading").getParent()).performClick());await("enter_send","",10);
        press("back");openList();press("new_chat");await("message","",10);
        final String[] saved={""};runOnMainSync(()->saved[0]=((EditText)view("message")).getText().toString());
        try{
            fill("message","请汇总本次核对结果。");boolean[] enabled={false};runOnMainSync(()->enabled[0]=view("send").isEnabled());
            if(!enabled[0])throw new AssertionError("Sending disabled for populated draft");
            press("list_chats");press("new_chat");await("message","请汇总本次核对结果。",8);imageOfPage("whitecat19-draft.png");
            fill("message","");runOnMainSync(()->enabled[0]=view("send").isEnabled());
            if(enabled[0])throw new AssertionError("Empty message can be sent");
        }finally{fill("message",saved[0]);}
        press("list_chats");fill("search","白猫流式验收18");await("list_rows","白猫流式验收18C",15);
        selectTaggedChat();await("status","实时同步",15);
    }
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{
            Intent i=new Intent().setClassName("dev.whitecat.desk","dev.whitecat.desk.ChatActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity=startActivitySync(i);
            String stage=options.getString("stage","");
            if(!stage.startsWith("personal_")&&!stage.equals("remote_deletion")&&!stage.equals("server_profiles")&&!stage.equals("executor_hierarchy")&&!stage.equals("live_directory")){
                View entry=activity.findViewById(android.R.id.content).findViewWithTag("entry_go");
                if(entry!=null)runOnMainSync(entry::performClick);
            }
            switch(stage){
                case "server_profiles":ServerProfilesCheck.run(this,result);break;
                case "executor_hierarchy":ExecutorHierarchyCheck.run(this,result);break;
                case "remote_deletion":RemoteDeletionCheck.run(this,result);break;
                case "live_directory": case "personal_choose": case "personal_resume":new PersonalizationCheck(this).run(stage,result);break;
                case "migration_export": case "migration_import": new MigrationCheck(this).run(stage,result);break;
                case "workspace_send": case "workspace_delete": case "workspace_retained": case "workspace_hidden": new WorkspaceCheck(this).run(stage,result);break;
                case "design_style": verifyDesign();break;
                case "send":
                    if(view("pair_code")!=null){
                        fill("endpoint",options.getString("endpoint"));fill("pair_code",options.getString("code"));press("pair_button");
                    }
                    await("new_chat","",30);
                    String prefill=options.getString("prompt");
                    if(view("retry")!=null&&view("retry").isShown())throw new AssertionError("Existing unsent task");
                    press("new_chat");waitForIdleSync();
                    fill("message",prefill);press("send");
                    await("hint","未收到回执",55);await("retry","按原请求重试",3);
                    break;
                case "offline":
                    await("retry","按原请求重试",10);press("retry");await("hint","未收到回执",50);break;
                case "resume":
                    await("retry","按原请求重试",10);press("retry");
                    await("transcript",options.getString("answer"),240);
                    fill("message",options.getString("follow"));press("send");
                    await("transcript",options.getString("final"),240);break;
                case "draft":
                    await("transcript",options.getString("answer"),35);
                    fill("message",options.getString("draft"));press("list_chats");
                    await("search","",5);fill("search","not-found-fdc2");await("list_rows","没有匹配",5);
                    fill("search","");await("list_rows","27",5);press("nav_account");await("enter_send","回车发送",5);
                    runOnMainSync(()->{
                        Switch toggle=(Switch)view("enter_send");boolean start=toggle.isChecked();
                        toggle.performClick();if(toggle.isChecked()==start)throw new AssertionError("Switch did not toggle");
                        toggle.performClick();
                    });press("back");openList();selectTaggedChat();
                    await("message",options.getString("draft"),5);verifyProjection(result);break;
                case "cached":
                    await("transcript",options.getString("answer"),15);await("message",options.getString("draft"),10);
                    await("status","本机缓存",40);press("list_chats");await("list_rows","27",5);
                    selectTaggedChat();verifyProjection(result);break;
                case "reconcile":
                    await("transcript",options.getString("remote"),80);await("message",options.getString("draft"),10);
                    press("send");await("transcript",options.getString("answer"),180);verifyProjection(result);break;
                case "shared_select":
                case "native_open":
                    openList();
                    fill("search",options.getString("title"));await("list_rows",options.getString("title"),20);
                    runOnMainSync(()->{
                        android.view.ViewGroup rows=(android.view.ViewGroup)view("list_rows");
                        for(int k=0;k<rows.getChildCount();k++){View card=rows.getChildAt(k);
                            if(card.isClickable()&&contents(card).contains(options.getString("title"))){card.performClick();break;}}
                    });
                    await("transcript",options.getString("first"),25);await("transcript","工作站 · Codex CLI",15);
                    if(stage.equals("shared_select")){verifyProjection(result);break;}
                    fill("message",options.getString("prompt"));press("send");await("transcript",options.getString("answer"),180);
                    fill("message",options.getString("draft"));verifyProjection(result);break;
                case "shared_send":
                    await("message","",10);fill("message",options.getString("prompt"));
                    long target=Long.parseLong(options.getString("at","0"));
                    if(target>0)SystemClock.sleep(Math.max(0,target-System.currentTimeMillis()));
                    press("send");result.putString("pressed",Long.toString(System.currentTimeMillis()));
                    if(options.containsKey("offline"))await("hint","未收到回执",50);
                    else await("transcript",options.getString("answer"),150);
                    break;
                case "stream_send":
                    await("status","实时同步",30);fill("message",options.getString("prompt"));press("send");
                    await("transcript",options.getString("marker"),30);break;
                case "stream_watch":{
                    if(options.containsKey("newPrompt")){
                        openList();await("list_rows","",10);
                        long findUntil=SystemClock.elapsedRealtime()+10000;boolean[] chosen={false};
                        while(!chosen[0]&&SystemClock.elapsedRealtime()<findUntil){
                            runOnMainSync(()->{ViewGroup rows=(ViewGroup)view("list_rows");
                                for(int iRow=0;rows!=null&&iRow<rows.getChildCount();iRow++){
                                    View card=rows.getChildAt(iRow);
                                    if(options.getString("chat").equals(card.getTag())){card.performClick();chosen[0]=true;break;}
                                }});
                            if(!chosen[0])SystemClock.sleep(100);
                        }
                        if(!chosen[0])throw new AssertionError("Target chat row unavailable");
                        await("status","实时同步",25);cache();
                        fill("message",options.getString("newPrompt"));press("send");
                    }
                    long end=SystemClock.elapsedRealtime()+190000,prev=-1;String prevLabel="";
                    while(SystemClock.elapsedRealtime()<end){
                        org.json.JSONObject[] snapshot={null};String[] visible={"",""};
                        runOnMainSync(()->{
                            try{snapshot[0]=cache();}catch(Exception e){return;}
                            visible[0]=contents(view("transcript"));visible[1]=contents(view("status"));
                        });
                        org.json.JSONObject local=snapshot[0];
                        if(local==null){SystemClock.sleep(55);continue;}
                        long seq=local.optLong("stream_cursor",-1);
                        String label=visible[1].trim();
                        if(seq!=prev||!label.equals(prevLabel)){
                            prev=seq;prevLabel=label;
                            org.json.JSONArray ts=local.getJSONArray("turns");org.json.JSONObject task=ts.getJSONObject(ts.length()-1);
                            if(task.optString("prompt").contains(options.getString("marker"))){
                                org.json.JSONArray parts=task.optJSONArray("parts");int words=0,tools=0,active=0;boolean finalVisible=false;
                                for(int j=0;parts!=null&&j<parts.length();j++){
                                    org.json.JSONObject part=parts.getJSONObject(j);String kind=part.optString("kind"),body=part.optString("body");
                                    if(kind.equals("answer")||kind.equals("commentary")){
                                        if(!body.isEmpty()&&visible[0].contains(body)){
                                            words+=body.length();if(kind.equals("answer")&&part.optString("status").equals("running"))finalVisible=true;
                                        }
                                    }else{tools++;if(part.optString("status").equals("running")&&visible[0].contains("执行中"))active++;}
                                }
                                Bundle sample=new Bundle();sample.putString("sample",seq+"|"+words+"|"+tools+"|"+active+"|"+finalVisible+"|"+task.optString("status")+"|"+label);sendStatus(1,sample);
                                if(task.optString("status").equals("done")&&visible[0].contains(task.optString("answer"))&&!task.optString("answer").isEmpty()){
                                    verifyProjection(result);break;
                                }
                            }
                        }
                        SystemClock.sleep(55);
                    }
                    if(!result.containsKey("projection"))throw new AssertionError("No final streamed transcript");
                    break;}
                case "shared_verify":
                    await("transcript",options.getString("answer"),100);verifyProjection(result);break;
                case "native_offline":
                    await("status","本机缓存",45);await("transcript",options.getString("answer"),15);
                    await("message",options.getString("draft"),15);verifyProjection(result);break;
                default: throw new AssertionError("Unknown stage");
            }
            result.putString("whitecat","PASS "+stage);finish(Activity.RESULT_OK,result);
        }catch(Throwable e){result.putString("whitecat","FAIL "+e.getClass().getSimpleName()+" "+e.getMessage());finish(Activity.RESULT_CANCELED,result);}
    }
}
