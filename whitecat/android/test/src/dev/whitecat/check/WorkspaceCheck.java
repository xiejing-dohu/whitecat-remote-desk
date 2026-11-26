package dev.whitecat.check;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.view.accessibility.*;
import android.widget.*;
import org.json.*;

final class WorkspaceCheck {
    final DeviceCheck d;
    WorkspaceCheck(DeviceCheck device){d=device;}
    SharedPreferences prefs(){
        SharedPreferences p=d.getTargetContext().getSharedPreferences("desk",0);
        String base=d.getTargetContext().getSharedPreferences("connection",0).getString("active","");
        if(!base.isEmpty()&&!base.equals(p.getString("base","http://127.0.0.1:18744")))return d.getTargetContext().getSharedPreferences("desk."+java.util.UUID.nameUUIDFromBytes(base.getBytes(java.nio.charset.StandardCharsets.UTF_8)),0);
        return p;
    }
    View match(View v,String text){
        if(v==null||v.getVisibility()!=View.VISIBLE)return null;
        if(text.contentEquals(v.getContentDescription()==null?"":v.getContentDescription())||
                (v instanceof TextView&&text.contentEquals(((TextView)v).getText())))return v;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){
            View found=match(((ViewGroup)v).getChildAt(i),text);if(found!=null)return found;
        }
        return null;
    }
    View find(String name){
        final View[] found={null};long deadline=SystemClock.elapsedRealtime()+18000;
        do{
            d.runOnMainSync(()->found[0]=match(d.activity.findViewById(android.R.id.content),name));
            if(found[0]!=null)return found[0];SystemClock.sleep(100);
        }while(SystemClock.elapsedRealtime()<deadline);
        throw new AssertionError("Missing UI "+name);
    }
    void click(String label){View v=find(label);d.runOnMainSync(()->v.performClick());}
    void edit(String label,String value){View v=find(label);d.runOnMainSync(()->{
        View field=v instanceof EditText?v:d.activity.findViewById(v.getLabelFor());if(!(field instanceof EditText))throw new AssertionError("Missing labeled edit field");((EditText)field).setText(value);
    });}
    void go(String tab){
        if(d.view("list_chats")!=null)d.press("list_chats");
        if(d.view("nav_home")==null&&d.view("back")!=null)d.press("back");
        d.press("nav_"+tab);
    }
    void dialog(String text){
        for(int i=0;i<70;i++){
            AccessibilityNodeInfo root=d.getUiAutomation().getRootInActiveWindow();
            if(root!=null)for(AccessibilityNodeInfo n:root.findAccessibilityNodeInfosByText(text)){
                if(text.contentEquals(n.getText()==null?"":n.getText())&&n.isClickable()&&n.performAction(AccessibilityNodeInfo.ACTION_CLICK)){SystemClock.sleep(220);return;}
            }
            SystemClock.sleep(80);
        }throw new AssertionError("Missing dialog choice "+text);
    }
    String arg(String key){return d.options.getString(key);}
    void listItem(String id,String filter,boolean open){
        go("list");d.fill("search",filter);d.await("list_rows",filter,15);
        final View[] card={null};d.runOnMainSync(()->card[0]=d.view("list_rows").findViewWithTag(id));
        if(card[0]==null)throw new AssertionError("Missing test row");
        d.runOnMainSync(()->{if(open)card[0].performClick();else card[0].findViewById(d.getTargetContext().getResources().getIdentifier("local_delete","id","dev.whitecat.desk")).performClick();});
    }
    void hidden(String id)throws Exception{
        SharedPreferences p=prefs();if(!p.getBoolean("hidden."+id,false)||p.contains("cache."+id)||p.contains("draft."+id))throw new AssertionError("Local copy remains");
        JSONArray index=new JSONArray(p.getString("index","[]"));for(int i=0;i<index.length();i++)if(id.equals(index.getJSONObject(i).optString("chat")))throw new AssertionError("Hidden row returned");
    }
    void run(String stage,Bundle result)throws Exception{
        String name=arg("agent"),marker=arg("marker"),id=arg("chat"),question=arg("question");
        if(stage.equals("workspace_send")){
            go("agents");click("聊天角色模板");click("添加角色模板");edit("名称",name);edit("角色指令","收到算术问题时给出结果。");click("保存");
            click("编辑 "+name);edit("角色指令","所有算术回答只输出 "+marker+"=<计算结果>，不要加空格或其他文字。");click("保存");
            find("与 "+name+" 开始会话");d.imageOfPage("20-agents.png");click("与 "+name+" 开始会话");
            d.await("message","",10);d.fill("message",question);d.press("send");d.await("transcript",marker+"=42",145);
            id=prefs().getString("chat","");if(id.length()!=36)throw new AssertionError("Missing chat binding");
            d.fill("message",arg("draft"));d.press("list_chats");d.press("nav_transfer");find("旧手机 · 生成迁移码");d.imageOfPage("20-transfer.png");
            result.putString("chat_id",id);
        }else if(stage.equals("workspace_delete")){
            go("agents");click("聊天角色模板");find("与 "+name+" 开始会话");d.imageOfPage("20-agents.png");
            go("transfer");find("旧手机 · 生成迁移码");d.imageOfPage("20-transfer.png");
            d.runOnMainSync(()->{
                for(String key:new String[]{"home","list","agents","transfer"}){
                    LinearLayout tab=(LinearLayout)d.view("nav_"+key);
                    TextView label=(TextView)tab.getChildAt(1);
                    if((label.getGravity()&Gravity.HORIZONTAL_GRAVITY_MASK)!=Gravity.CENTER_HORIZONTAL)throw new AssertionError("Tab label alignment");
                }
            });
            if(!prefs().contains("draft."+id)||!prefs().contains("cache."+id))throw new AssertionError("Expected cached demo data");
            listItem(id,question,false);dialog("取消");if(!prefs().contains("cache."+id))throw new AssertionError("Cancel removed data");
            listItem(id,question,false);dialog("仅从本机删除");SystemClock.sleep(2600);hidden(id);
            go("agents");click("聊天角色模板");click("编辑 "+name);click("归档角色模板");dialog("归档");
            click("与 Codex 开始会话");d.press("list_chats");result.putString("source","hidden");
        }else if(stage.equals("workspace_retained")){
            SharedPreferences p=prefs();if(p.getBoolean("hidden."+id,false)||!p.contains("cache."+id))throw new AssertionError("Missing on receiver");
            listItem(id,question,true);d.await("transcript",marker+"=42",20);d.await("message",arg("draft"),10);
            d.fill("message","在刚才的计算结果上再加一。");d.press("send");d.await("transcript",marker+"=43",145);
            d.imageOfPage("20-followup.png");result.putString("receiver","continued");
        }else if(stage.equals("workspace_hidden")){
            go("list");d.fill("search",question);d.await("list_rows","没有匹配",15);SystemClock.sleep(2600);hidden(id);
            go("home");d.await("home_recent","",10);SystemClock.sleep(2400);
            d.runOnMainSync(()->{if(d.view("home_recent").findViewWithTag(arg("chat"))!=null)throw new AssertionError("Deleted chat in home");});
            result.putString("source","hidden_after_restart");
        }
    }
}
