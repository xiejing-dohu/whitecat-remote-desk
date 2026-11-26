package dev.whitecat.check;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.InetAddress;
import java.net.Socket;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.DataInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

final class MigrationCheck {
    final DeviceCheck d;
    final WorkspaceCheck workspace;
    final SharedPreferences prefs;
    final String chat, file;

    MigrationCheck(DeviceCheck device) {
        d=device;
        workspace=new WorkspaceCheck(d);
        prefs=workspace.prefs();
        chat=d.options.getString("chat","");
        file=d.options.getString("file","");
        require(chat.matches("[a-f0-9-]{36}") && file.matches("wcqr-[a-f0-9]{12}\\.png"),"Invalid test input");
    }

    static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}

    static String canonical(Object value) throws Exception {
        if(value instanceof JSONObject){
            JSONObject object=(JSONObject)value;
            List<String> keys=new ArrayList<>();
            for(Iterator<String> i=object.keys();i.hasNext();)keys.add(i.next());
            Collections.sort(keys);
            List<String> items=new ArrayList<>();
            for(String key:keys)items.add(JSONObject.quote(key)+":"+canonical(object.get(key)));
            return "{"+android.text.TextUtils.join(",",items)+"}";
        }
        if(value instanceof JSONArray){
            JSONArray array=(JSONArray)value;List<String> items=new ArrayList<>();
            for(int i=0;i<array.length();i++)items.add(canonical(array.get(i)));
            return "["+android.text.TextUtils.join(",",items)+"]";
        }
        return value instanceof String ? JSONObject.quote((String)value) : String.valueOf(value);
    }

    static String hash(Object value) throws Exception {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(canonical(value).getBytes(StandardCharsets.UTF_8));
        StringBuilder out=new StringBuilder();
        for(byte b:digest)out.append(String.format(Locale.ROOT,"%02x",b&255));
        return out.toString();
    }

    void snapshot(Bundle out) throws Exception {
        JSONObject cache=new JSONObject(prefs.getString("cache."+chat,"{}"));
        cache.remove("online"); // Presence is transient and does not belong to conversation history.
        JSONArray turns=cache.getJSONArray("turns");
        require(turns.length()>0 && !cache.isNull("native_thread"),"Missing real conversation");
        JSONObject last=turns.getJSONObject(turns.length()-1);
        require("done".equals(last.getString("status")) && !last.getString("answer").trim().isEmpty(),"Missing completed answer");
        JSONObject draft=new JSONObject(prefs.getString("draft."+chat,"{}"));
        require(d.options.getString("draft").equals(draft.getString("prompt")),"Draft content differs");
        out.putString("history_sha256",hash(cache));out.putString("draft_sha256",hash(draft));
        out.putString("turns",String.valueOf(turns.length()));
        out.putString("revision",String.valueOf(cache.getLong("revision")));
        out.putString("cursor",String.valueOf(cache.getLong("stream_cursor")));
    }

    View tagged(Activity activity,String tag){
        long until=SystemClock.elapsedRealtime()+20000;
        View[] found={null};
        do{
            d.runOnMainSync(()->{
                View root=activity.findViewById(android.R.id.content);
                found[0]=root==null?null:root.findViewWithTag(tag);
            });
            if(found[0]!=null && found[0].isShown())return found[0];
            SystemClock.sleep(100);
        }while(SystemClock.elapsedRealtime()<until);
        throw new AssertionError("Missing "+tag);
    }
    void click(View v){
        boolean[] enabled={false};
        d.runOnMainSync(()->{enabled[0]=v.isEnabled();if(enabled[0])v.performClick();});
        require(enabled[0],"Disabled migration control");
    }
    void click(String tag){click(tagged(d.activity,tag));}

    void exportCode(Bundle out)throws Exception {
        require(!prefs.getBoolean("hidden."+chat,false),"Fixture hidden on sender");
        require(prefs.contains("cache."+chat),"Fixture cache absent on sender");
        require(prefs.contains("draft."+chat),"Fixture draft absent on sender");
        require(!prefs.getString("access","").isEmpty(),"Sender is unpaired");
        workspace.listItem(chat,d.options.getString("question"),true);
        d.await("message",d.options.getString("draft"),15);
        require(chat.equals(prefs.getString("chat","")),"Incorrect fixture on sender");
        long deadline=SystemClock.elapsedRealtime()+30000;
        boolean done=false;
        do{
            JSONArray turns=new JSONObject(prefs.getString("cache."+chat,"{}")).optJSONArray("turns");
            if(turns!=null && turns.length()>0 && "done".equals(turns.getJSONObject(turns.length()-1).optString("status"))){done=true;break;}
            SystemClock.sleep(150);
        }while(SystemClock.elapsedRealtime()<deadline);
        require(done,"Sender did not synchronize its completed turn");
        workspace.go("transfer");click("migration_old");
        CompoundButton current=(CompoundButton)tagged(d.activity,"migration_current");
        require(current.isEnabled() && current.isChecked(),"Current conversation scope unavailable");
        for(String name:new String[]{"migration_chats","migration_drafts","migration_settings"}){
            CompoundButton choice=(CompoundButton)tagged(d.activity,name);
            boolean wanted=!name.equals("migration_settings");
            if(choice.isChecked()!=wanted)click(choice);
            require(choice.isChecked()==wanted,"Wrong export selection");
        }
        snapshot(out);click("migration_generate");
        ImageView image=(ImageView)tagged(d.activity,"migration_qr");
        Bitmap[] code={null};
        d.runOnMainSync(()->code[0]=((BitmapDrawable)image.getDrawable()).getBitmap());
        int port=Integer.parseInt(d.options.getString("port"));
        try(Socket socket=new Socket(InetAddress.getByName("127.0.0.1"),port)){
            socket.setSoTimeout(12000);
            ByteArrayOutputStream pixels=new ByteArrayOutputStream();
            require(code[0].compress(Bitmap.CompressFormat.PNG,100,pixels),"Unable to encode QR pixels");
            DataOutputStream stream=new DataOutputStream(socket.getOutputStream());
            stream.write((file+"\n").getBytes(StandardCharsets.US_ASCII));
            stream.writeInt(pixels.size());pixels.writeTo(stream);stream.flush();
            require(socket.getInputStream().read()==1,"Host did not acknowledge image");
        }
        click("migration_return");
    }

    // The receiver owns this single MediaStore row. DocumentsUI grants the actual selected URI.
    Uri receiveImage()throws Exception {
        ContentResolver resolver=d.getTargetContext().getContentResolver();
        ContentValues values=new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME,file);
        values.put(MediaStore.MediaColumns.MIME_TYPE,"image/png");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/");
        values.put(MediaStore.MediaColumns.IS_PENDING,1);
        Uri row=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
        require(row!=null,"Unable to create temporary Downloads entry");
        try {
            int port=Integer.parseInt(d.options.getString("port"));
            try(Socket socket=new Socket(InetAddress.getByName("127.0.0.1"),port)){
                socket.setSoTimeout(12000);
                socket.getOutputStream().write((file+"\n").getBytes(StandardCharsets.US_ASCII));
                DataInputStream in=new DataInputStream(socket.getInputStream());
                int count=in.readInt();
                require(count>=128 && count<=256000,"Unexpected image size");
                byte[] pixels=new byte[count];in.readFully(pixels);
                try(OutputStream out=resolver.openOutputStream(row)){out.write(pixels);}
                socket.getOutputStream().write(1);
            }
            values.clear();values.put(MediaStore.MediaColumns.IS_PENDING,0);
            require(resolver.update(row,values,null,null)==1,"Unable to expose selected image");
            return row;
        }catch(Exception|AssertionError failure){resolver.delete(row,null,null);throw failure;}
    }

    AccessibilityNodeInfo matchNode(AccessibilityNodeInfo root,String key,boolean id){
        if(root==null)return null;
        if(id ? key.equals(root.getViewIdResourceName())
              : key.contentEquals(root.getText()==null?"":root.getText()) || key.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))
            return root;
        for(int i=0;i<root.getChildCount();i++){
            AccessibilityNodeInfo found=matchNode(root.getChild(i),key,id);
            if(found!=null)return found;
        }
        return null;
    }
    boolean clickNode(AccessibilityNodeInfo node){
        while(node!=null){
            if(node.isEnabled() && node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK))return true;
            node=node.getParent();
        }
        return false;
    }
    void chooseFromDocuments(){
        boolean openedRoots=false, openedVolume=false, openedDownload=false;
        long start=SystemClock.elapsedRealtime(),until=start+22000;
        while(SystemClock.elapsedRealtime()<until){
            AccessibilityNodeInfo root=d.getUiAutomation().getRootInActiveWindow();
            String pkg=root==null?"":String.valueOf(root.getPackageName());
            if(pkg.endsWith(".documentsui")){
                if(clickNode(matchNode(root,file,false))||clickNode(matchNode(root,file.substring(0,file.length()-4),false)))return;
                if(SystemClock.elapsedRealtime()-start>1200){
                    if(!openedVolume && clickNode(matchNode(root,android.os.Build.MODEL,false))){
                        openedVolume=true;
                    }else if(openedVolume && !openedDownload){
                        openedDownload=clickNode(matchNode(root,Environment.DIRECTORY_DOWNLOADS,false));
                    }else if(!openedRoots){
                        for(String desc:new String[]{"Show roots","Show navigation drawer","显示根目录","显示导航抽屉"}){
                            if(clickNode(matchNode(root,desc,false))){openedRoots=true;break;}
                        }
                    }
                }
            }
            SystemClock.sleep(120);
        }
        throw new AssertionError("DocumentsUI image unavailable roots "+openedRoots+" volume "+openedVolume+" directory "+openedDownload);
    }
    String scan(Uri selected) throws Exception {
        Instrumentation.ActivityMonitor monitor=d.addMonitor("dev.whitecat.desk.MigrationScanner",null,false);
        Activity scanner;
        try{
            click("migration_new");
            scanner=d.waitForMonitorWithTimeout(monitor,10000);
            require(scanner!=null,"Scanner activity did not launch");
        }finally{d.removeMonitor(monitor);}
        View image=tagged(scanner,"migration_image");
        // A one-time grant leaves camera access transient if Android asks on first use.
        long until=SystemClock.elapsedRealtime()+5000;
        while(SystemClock.elapsedRealtime()<until){
            AccessibilityNodeInfo root=d.getUiAutomation().getRootInActiveWindow();
            String pkg=root==null?"":String.valueOf(root.getPackageName());
            if(pkg.endsWith(".permissioncontroller")){
                if(clickNode(matchNode(root,pkg+":id/permission_allow_one_time_button",true)))break;
            }else if("dev.whitecat.desk".equals(pkg) && scanner.hasWindowFocus())break;
            SystemClock.sleep(100);
        }
        if(!"content-uri".equals(d.options.getString("picker"))){
            click(image);
            try{chooseFromDocuments();return "DocumentsUI";}
            catch(AssertionError unavailable){
            for(int i=0;i<8;i++){
                AccessibilityNodeInfo root=d.getUiAutomation().getRootInActiveWindow();
                if(root!=null && String.valueOf(root.getPackageName()).endsWith(".documentsui"))
                    d.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
                SystemClock.sleep(150);
                if(scanner.hasWindowFocus())break;
            }
            require(scanner.hasWindowFocus(),"System picker did not return to scanner");
            }
        }
        IntentFilter filter=new IntentFilter(Intent.ACTION_OPEN_DOCUMENT);
        filter.addCategory(Intent.CATEGORY_OPENABLE);filter.addDataType("image/*");
        Instrumentation.ActivityResult result=new Instrumentation.ActivityResult(Activity.RESULT_OK,new Intent().setData(selected));
        Instrumentation.ActivityMonitor picker=d.addMonitor(filter,result,true);
        try{
            click(image);require(picker.getHits()==1,"Image selection action was not dispatched");
        }finally{d.removeMonitor(picker);}
        return "Instrumentation ACTION_OPEN_DOCUMENT result with owned MediaStore content URI";
    }

    void absent(){
        require(!prefs.contains("cache."+chat)&&!prefs.contains("draft."+chat),"Receiver already has cache or draft");
        require(!chat.equals(prefs.getString("chat","")),"Receiver selected target before import");
    }
    void unchanged(String access){
        require(!access.isEmpty() && access.equals(prefs.getString("access","")),"Receiver pairing changed");
        require(!chat.equals(prefs.getString("chat","")),"Receiver opened target chat");
    }
    String indexHash()throws Exception {
        JSONArray index=new JSONArray(prefs.getString("index","[]"));int count=0;
        for(int i=0;i<index.length();i++)if(chat.equals(index.getJSONObject(i).optString("chat")))count++;
        require(count==1,"Expected one imported conversation index row");
        return hash(index);
    }
    void importCode(Bundle out)throws Exception {
        String access=prefs.getString("access","");
        absent();require(!access.isEmpty(),"Receiver must be paired");
        workspace.go("transfer");
        Uri row=receiveImage();
        ContentResolver resolver=d.getTargetContext().getContentResolver();
        try{
            String firstPicker=scan(row);
            View confirm=tagged(d.activity,"migration_confirm");
            String summary=d.contents(d.activity.findViewById(android.R.id.content));
            require(summary.contains("会话索引 1 条，缓存记录 1 份") && summary.contains("草稿 1 份") && !summary.contains("设置及 Agent 引用"),"Unexpected migration preview");
            absent();unchanged(access);
            click(confirm);
            require("资料已导入".contentEquals(((TextView)tagged(d.activity,"migration_done")).getText()),"Missing import receipt");
            unchanged(access);snapshot(out);
            for(String key:new String[]{"history_sha256","draft_sha256"})
                require(out.getString(key).equals(d.options.getString(key)),"Imported "+key+" differs");
            String index=indexHash();
            long until=SystemClock.elapsedRealtime()+15000;
            while(prefs.contains("migration.receipt") && SystemClock.elapsedRealtime()<until)SystemClock.sleep(100);
            require(!prefs.contains("migration.receipt"),"Workstation did not acknowledge import");
            click("migration_return");String repeatPicker=scan(row);
            require("这份资料已导入本机".contentEquals(((TextView)tagged(d.activity,"migration_done")).getText()),"Duplicate scan did not yield already-imported state");
            Bundle again=new Bundle();snapshot(again);
            for(String key:out.keySet())require(out.getString(key).equals(again.getString(key)),"Duplicate scan mutated "+key);
            require(index.equals(indexHash()),"Duplicate scan mutated conversation index");
            unchanged(access);
            out.putString("index_sha256",index);
            out.putString("first_picker",firstPicker);out.putString("repeat_picker",repeatPicker);
            click("migration_return");
        }finally{require(resolver.delete(row,null,null)==1,"Could not remove temporary Downloads entry");}
    }
    void run(String stage,Bundle out)throws Exception {
        if("migration_export".equals(stage))exportCode(out);else importCode(out);
    }
}
