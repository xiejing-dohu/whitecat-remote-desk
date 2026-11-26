package dev.whitecat.check;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

/** Two on-device loopback gateways with independent one-time pairing codes and bearer credentials. */
final class ServerProfilesCheck {
    private final DeviceCheck d;
    private ServerProfilesCheck(DeviceCheck check){d=check;}

    private View tag(String tag) {
        long until=SystemClock.elapsedRealtime()+15000;
        do {
            View[] found={null};
            d.runOnMainSync(()->{
                View root=d.activity.findViewById(android.R.id.content);
                if(root!=null) found[0]=root.findViewWithTag(tag);
            });
            if(found[0]!=null) return found[0];
            SystemClock.sleep(65);
        } while(SystemClock.elapsedRealtime()<until);
        throw new AssertionError("Missing "+tag);
    }
    private void click(String key) {
        View view=tag(key);
        d.runOnMainSync(()->{if(!view.performClick()) throw new AssertionError(key);});
    }
    private void type(String key,String value) {
        View field=tag(key);d.runOnMainSync(()->((EditText)field).setText(value));
    }
    private void directory() {
        d.await("nav_account","",12);d.press("nav_account");click("settings_servers");tag("servers_add");
    }
    private void add(String name,String address) {
        click("servers_add");type("server_name",name);type("server_address",address);click("server_pair");
    }
    private AccessibilityNodeInfo field(AccessibilityNodeInfo node) {
        if(node==null)return null;
        if(node.isEditable())return node;
        for(int i=0;i<node.getChildCount();i++){
            AccessibilityNodeInfo found=field(node.getChild(i));if(found!=null)return found;
        }
        return null;
    }
    private void rename(Station station,String name) {
        click("server_rename_"+station.url);
        waitUntil(()->{
            AccessibilityNodeInfo edit=field(d.getUiAutomation().getRootInActiveWindow());
            if(edit==null)return false;
            Bundle text=new Bundle();text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,name);
            return edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,text);
        },"rename field");
        new WorkspaceCheck(d).dialog("保存");
        waitUntil(()->{
            boolean[] changed={false};d.runOnMainSync(()->changed[0]=d.contents(d.activity.findViewById(android.R.id.content)).contains(name));return changed[0];
        },"rename persistence");
    }
    private void pair(Station station) {
        add(station.name,station.url);
        d.await("endpoint",station.url,8);d.fill("pair_code",station.code);d.press("pair_button");
        d.await("nav_home","",15);
        waitUntil(()->station.states.get()>0,"gateway authentication");
    }
    interface Condition {boolean success();}
    private static void waitUntil(Condition condition,String what) {
        long end=SystemClock.elapsedRealtime()+15000;
        while(!condition.success() && SystemClock.elapsedRealtime()<end) SystemClock.sleep(70);
        if(!condition.success())throw new AssertionError(what);
    }
    static String profileFile(String url) {
        return "desk."+UUID.nameUUIDFromBytes(url.getBytes(StandardCharsets.UTF_8));
    }
    static void run(DeviceCheck d,Bundle result)throws Exception {
        new ServerProfilesCheck(d).exercise(result);
    }
    private void exercise(Bundle result)throws Exception {
        Context context=d.getTargetContext();
        SharedPreferences connection=context.getSharedPreferences("connection",0);
        String previous=connection.getString("active",null),directory=connection.getString("servers_v1",null);
        try(Station a=new Station("测试服务器甲");Station b=new Station("测试服务器乙")) {
            SharedPreferences first=context.getSharedPreferences(profileFile(a.url),0);
            SharedPreferences second=context.getSharedPreferences(profileFile(b.url),0);
            if(!first.getAll().isEmpty()||!second.getAll().isEmpty())throw new AssertionError("Fixture ports already registered");
            try {
                RemoteDeletionCheck.mainThread(d,()->{
                    RemoteDeletionCheck.set(d.activity,"prefs",first);
                    RemoteDeletionCheck.set(d.activity,"token","");
                });
                click("entry_go");d.await("pair_code","",8);
                d.fill("endpoint",a.url);d.fill("pair_code","112233");click("pair_settings");
                click("settings_about");d.press("back");d.press("back");
                d.await("endpoint",a.url,6);d.await("pair_code","112233",6);
                d.press("back");click("entry_go");click("pair_settings");click("settings_servers");pair(a);
                waitUntil(()->first.getString("index","").contains(a.name),"first cache");
                String marker=UUID.randomUUID().toString();
                first.edit().putString("draft."+Station.CHAT,new JSONObject().put("prompt",marker).toString())
                        .putString("selected_executor","alpha-runner").commit();
                directory();pair(b);
                waitUntil(()->second.getString("index","").contains(b.name),"second cache");
                if(second.contains("draft."+Station.CHAT)||second.contains("selected_executor")||second.getString("index","").contains(a.name))
                    throw new AssertionError("Profiles shared content");
                if(first.getString("access","").isEmpty()||second.getString("access","").isEmpty()
                        ||first.getString("access","").equals(second.getString("access","")))
                    throw new AssertionError("Expected independent encrypted credentials");

                directory();
                d.imageOfPage("22-server-profiles.png");
                String renamed="甲的本地显示名称";rename(a,renamed);
                a.mode="offline";
                int before=a.attempts.get();click("server_open_"+a.url);d.await("nav_home","",12);
                waitUntil(()->a.attempts.get()>before,"offline switch attempted correct gateway");
                if(!a.url.equals(connection.getString("active",""))||!first.getString("draft."+Station.CHAT,"").contains(marker)
                        ||!"alpha-runner".equals(first.getString("selected_executor","")))
                    throw new AssertionError("Offline profile binding");
                d.runOnMainSync(()->{
                    String screen=d.contents(d.activity.findViewById(android.R.id.content));
                    if(!screen.contains(renamed)||screen.contains(b.name))throw new AssertionError("Visible offline identity");
                });

                a.mode="online";directory();add("重复地址",b.url+"/");
                int n=0;JSONArray saved=new JSONArray(connection.getString("servers_v1","[]"));
                for(int i=0;i<saved.length();i++) if(saved.getJSONObject(i).getString("url").equals(b.url))n++;
                if(n!=1)throw new AssertionError("Duplicate canonical endpoint");
                d.await("nav_home","",12);
                if(!b.url.equals(connection.getString("active","")))throw new AssertionError("Cached pairing not selected");
                b.mode="revoked";d.await("pair_code","",12);d.await("endpoint",b.url,5);
                if(!second.getBoolean("pair_required",false)||first.getBoolean("pair_required",false))
                    throw new AssertionError("Reauthentication affected wrong profile");
                if(a.foreign.get()!=0||b.foreign.get()!=0||a.pairs.get()!=1||b.pairs.get()!=1)
                    throw new AssertionError("Repeated pairing or cross-server credential");
                result.putString("server_profiles","two independent HTTP pairings; authorized switching; separate drafts/index; offline identity and local rename; canonical dedupe; scoped 401");
            } finally {
                d.runOnMainSync(()->d.activity.finish());d.waitForIdleSync();
                SharedPreferences.Editor restore=connection.edit();
                if(previous==null)restore.remove("active");else restore.putString("active",previous);
                if(directory==null)restore.remove("servers_v1");else restore.putString("servers_v1",directory);
                restore.commit();
                context.deleteSharedPreferences(profileFile(a.url));context.deleteSharedPreferences(profileFile(b.url));
            }
        }
    }

    private static final class Station implements AutoCloseable {
        static final String CHAT="8a376904-7a27-40e3-b88e-bdd287994845";
        final ServerSocket listener;
        final String url,name,instance=UUID.randomUUID().toString(),token=UUID.randomUUID().toString();
        final String code=UUID.randomUUID().toString().replace("-","").substring(0,12);
        final AtomicInteger pairs=new AtomicInteger(),states=new AtomicInteger(),foreign=new AtomicInteger(),attempts=new AtomicInteger();
        volatile String mode="online";
        Station(String name)throws IOException {
            this.name=name;
            listener=new ServerSocket(0,12,InetAddress.getByName("127.0.0.1"));
            url="http://127.0.0.1:"+listener.getLocalPort();
            Thread thread=new Thread(()->{
                while(!listener.isClosed()) try(Socket socket=listener.accept()){reply(socket);}
                catch(Exception ignored){}
            });
            thread.setDaemon(true);thread.start();
        }
        private void reply(Socket socket)throws Exception {
            socket.setSoTimeout(3000);
            BufferedReader r=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
            String request=r.readLine();if(request==null)return;
            String path=request.split(" ")[1],auth="",line;
            int size=0;
            while((line=r.readLine())!=null&&!line.isEmpty()) {
                int colon=line.indexOf(':');if(colon<1)continue;
                String key=line.substring(0,colon), value=line.substring(colon+1).trim();
                if("Authorization".equalsIgnoreCase(key))auth=value;
                if("Content-Length".equalsIgnoreCase(key))size=Integer.parseInt(value);
            }
            int status=200;
            JSONObject data=new JSONObject();
            if("/pair".equals(path)){
                char[] body=new char[size];int offset=0;
                while(offset<size){int n=r.read(body,offset,size-offset);if(n<0)throw new EOFException();offset+=n;}
                if(!auth.isEmpty())foreign.incrementAndGet();
                if(!code.equals(new JSONObject(new String(body)).optString("code"))||pairs.get()!=0)status=403;
                else{pairs.incrementAndGet();data.put("token",token);}
            } else if(!("Bearer "+token).equals(auth)){
                foreign.incrementAndGet();status=401;
            } else if("/api/state".equals(path)){
                attempts.incrementAndGet();
                if(mode.equals("offline"))status=503;
                else if(mode.equals("revoked"))status=401;
                else{
                    states.incrementAndGet();
                    JSONObject worker=new JSONObject().put("id","default").put("name",name).put("online",true);
                    JSONObject chat=new JSONObject().put("chat",CHAT).put("title",name+"的会话")
                            .put("executor","default").put("status","done").put("created",1);
                    data.put("station_name",name).put("online",true).put("instance",instance)
                            .put("chats_complete",true).put("executors",new JSONArray().put(worker)).put("chats",new JSONArray().put(chat));
                }
            } else if("/api/agents".equals(path))data.put("agents",new JSONArray());
            else status=404;
            OutputStream out=socket.getOutputStream();byte[] payload=data.toString().getBytes(StandardCharsets.UTF_8);
            out.write(("HTTP/1.1 "+status+" Check\r\nContent-Type: application/json\r\nContent-Length: "+payload.length
                    +"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(payload);out.flush();
        }
        public void close()throws IOException{listener.close();}
    }
}
