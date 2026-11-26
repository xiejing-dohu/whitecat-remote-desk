package dev.whitecat.check;

import android.content.*;
import android.os.*;
import android.view.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

/** Only a temporary profile and a random loopback listener are used. */
final class RemoteDeletionCheck {
    static void set(Object target,String key,Object value)throws Exception {
        Field field=target.getClass().getDeclaredField(key);field.setAccessible(true);field.set(target,value);
    }
    static void invoke(Object target,String method)throws Exception {
        Method call=target.getClass().getDeclaredMethod(method);call.setAccessible(true);call.invoke(target);
    }
    interface Operation {void run() throws Exception;}
    static void mainThread(DeviceCheck check,Operation step){
        Throwable[] failure={null};
        check.runOnMainSync(()->{try{step.run();}catch(Throwable e){failure[0]=e;}});
        if(failure[0]!=null)throw new AssertionError(failure[0]);
    }
    static void enabled(DeviceCheck c,boolean expected){
        mainThread(c,()->{
            for(String id:new String[]{"message","send","attachment"}){
                if(c.view(id).isEnabled()!=expected)throw new AssertionError("Unexpected "+id+" control");
            }
        });
    }
    static void waitForCounter(AtomicInteger count,int before){
        long until=SystemClock.elapsedRealtime()+10000;
        while(count.get()<=before&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(60);
        if(count.get()<=before)throw new AssertionError("No response from fixture");
    }
    static Object migration(String name,Class<?>[] types,Object...args)throws Exception {
        Method method=Class.forName("dev.whitecat.desk.MigrationData").getDeclaredMethod(name,types);
        method.setAccessible(true);return method.invoke(null,args);
    }
    static void checkOfflineTransfer(DeviceCheck c,Station station,SharedPreferences sender,SharedPreferences receiver)throws Exception{
        Class<?> prefsType=SharedPreferences.class;
        JSONObject exported=(JSONObject)migration("snapshot",new Class<?>[]{prefsType,boolean.class,boolean.class,boolean.class,String.class},
                sender,true,true,false,station.chat);
        byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);
        String encrypted=(String)migration("encrypt",new Class<?>[]{JSONObject.class,byte[].class},exported,key);
        JSONObject decoded=(JSONObject)migration("decrypt",new Class<?>[]{String.class,byte[].class},encrypted,key);
        Class<?>[] merge={prefsType,JSONObject.class,String.class};
        migration("merge",merge,receiver,decoded,"fixture-receipt");
        JSONObject imported=new JSONArray(receiver.getString("index","[]")).getJSONObject(0);
        JSONObject cache=new JSONObject(receiver.getString("cache."+station.chat,"{}"));
        if(!station.instance.equals(imported.getString("remote_instance"))||!"chat_deleted".equals(imported.getString("remote_state"))
                ||!station.instance.equals(cache.getString("instance")))throw new AssertionError("Lost portable station/deletion metadata");
        receiver.edit().putString("chat",station.chat).commit();
        mainThread(c,()->{set(c.activity,"prefs",receiver);invoke(c.activity,"restoreProfile");invoke(c.activity,"chatPage");});
        c.await("status","远程已删除",5);c.await("transcript","保留的回答",5);c.await("message","保留的草稿",5);enabled(c,false);
        receiver.edit().putBoolean("hidden."+station.chat,true).putString("index","[]").remove("cache."+station.chat)
                .remove("draft."+station.chat).commit();
        migration("merge",merge,receiver,decoded,"fixture-receipt");
        if(new JSONArray(receiver.getString("index","[]")).length()!=0||receiver.contains("cache."+station.chat)
                ||receiver.contains("draft."+station.chat))throw new AssertionError("Transfer resurrected receiver's locally hidden chat");
        mainThread(c,()->{set(c.activity,"prefs",sender);invoke(c.activity,"restoreProfile");invoke(c.activity,"chatPage");});
    }
    static void run(DeviceCheck c,Bundle result)throws Exception {
        try(Station station=new Station()){
            Context context=c.getTargetContext();
            String profile="remote-deletion-test."+UUID.randomUUID();
            SharedPreferences test=context.getSharedPreferences(profile,Context.MODE_PRIVATE);
            try{
                test.edit().putInt("interval",350).commit();
                mainThread(c,()->{
                    invoke(c.activity,"stopFeed");set(c.activity,"prefs",test);set(c.activity,"base",station.base());
                    set(c.activity,"token","fixture");set(c.activity,"pending","");set(c.activity,"chat","");
                    invoke(c.activity,"restoreProfile");
                    Field f=c.activity.getClass().getDeclaredField("dashboard");f.setAccessible(true);
                    invoke(f.get(c.activity),"show");
                });
                c.options.putString("chat",station.chat);
                c.await("home_recent","隔离测试",12);
                c.openList();c.await("list_rows","隔离测试",10);
                int before=station.states.get();station.mode="partial";waitForCounter(station.states,before);
                mainThread(c,()->{
                    String rows=c.contents(c.view("list_rows"));
                    if(!rows.contains("隔离测试")||rows.contains("远程已删除"))throw new AssertionError("Partial index");
                });
                station.mode="other";c.await("list_rows","工作站待核实",10);
                station.mode="live";
                long until=SystemClock.elapsedRealtime()+10000;
                while(test.getString("index","").contains("unverified")&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(80);
                c.selectTaggedChat();c.await("transcript","保留的回答",12);c.fill("message","保留的草稿");enabled(c,true);
                station.mode="native";c.await("status","原生历史不可用",12);enabled(c,false);
                station.mode="live";c.await("status","实时同步",12);enabled(c,true);
                station.mode="proxy404";c.await("status","本机缓存",15);enabled(c,true);
                int errorBefore=station.errors.get();station.mode="503";waitForCounter(station.errors,errorBefore);
                enabled(c,true);station.mode="deleted";c.await("status","远程已删除",16);
                c.await("transcript","保留的回答",5);c.await("message","保留的草稿",5);enabled(c,false);c.imageOfPage("21-remote-deleted.png");
                c.openList();c.await("list_rows","远程已删除",8);c.press("nav_home");c.await("home_recent","远程已删除",8);
                if(!test.getString("cache."+station.chat,"").contains("保留的回答")
                        ||!test.getString("draft."+station.chat,"").contains("保留的草稿"))
                    throw new AssertionError("Lost cache or draft");
                station.mode="503";
                mainThread(c,()->{invoke(c.activity,"restoreProfile");invoke(c.activity,"chatPage");});
                c.await("status","远程已删除",6);enabled(c,false);
                checkOfflineTransfer(c,station,test,context.getSharedPreferences(profile+".receiver",Context.MODE_PRIVATE));
                station.mode="live";c.await("status","实时同步",12);enabled(c,true);
                c.openList();station.mode="gone";c.await("list_rows","远程已删除",12);
                mainThread(c,()->{
                    Method remove=c.activity.getClass().getDeclaredMethod("deleteLocal",String.class);
                    remove.setAccessible(true);remove.invoke(c.activity,station.chat);
                });
                before=station.states.get();station.mode="live";waitForCounter(station.states,before);
                if(test.getString("index","").contains(station.chat)||!test.getBoolean("hidden."+station.chat,false))
                    throw new AssertionError("Local hiding did not win");
                if(station.jobs.get()!=0)throw new AssertionError("Read-only chat submitted a job");
                result.putString("remote_deletion","stream + index + cache + draft + home + list + controls + restore + local hide");
                result.putString("migration","encrypted offline transfer preserves deletion, source, transcript and draft; receiver local hide wins");
                result.putString("negative_cases","partial index, changed instance, native history recovery, proxy 404, HTTP 503");
            }finally{
                mainThread(c,()->{invoke(c.activity,"stopFeed");c.activity.finish();});
                context.deleteSharedPreferences(profile);context.deleteSharedPreferences(profile+".receiver");
            }
        }
    }
    static final class Station implements AutoCloseable {
        final String instance=UUID.randomUUID().toString(), other=UUID.randomUUID().toString(),
                chat=UUID.randomUUID().toString(),turn=UUID.randomUUID().toString();
        final ServerSocket server=new ServerSocket(0,16,InetAddress.getByName("127.0.0.1"));
        final AtomicInteger states=new AtomicInteger(),errors=new AtomicInteger(),jobs=new AtomicInteger();
        volatile String mode="live";
        Station()throws IOException{
            Thread listener=new Thread(()->{
                while(!server.isClosed())try{
                    Socket socket=server.accept();
                    Thread thread=new Thread(()->reply(socket));thread.setDaemon(true);thread.start();
                }catch(IOException ignored){}
            });
            listener.setDaemon(true);listener.start();
        }
        String base(){return "http://127.0.0.1:"+server.getLocalPort();}
        void reply(Socket s){
            try(Socket socket=s){
                BufferedReader input=new BufferedReader(new InputStreamReader(s.getInputStream(),"UTF-8"));
                String first=input.readLine(),line;
                while((line=input.readLine())!=null&&!line.isEmpty()){}
                String path=first.split(" ")[1],current=mode;int status=200;String mime="application/json";
                JSONObject data=new JSONObject().put("instance",instance);String body;
                if(path.equals("/api/state")){
                    JSONArray rows=new JSONArray();
                    if(current.equals("503"))status=503;
                    if(current.equals("other"))data.put("instance",other);
                    if(current.equals("live")||current.equals("native")||current.equals("proxy404")){
                        rows.put(new JSONObject().put("chat",chat).put("title","隔离测试").put("status","done")
                                .put("created",1).put("native_unavailable",current.equals("native")));
                    }
                    data.put("online",true).put("chats_complete",!current.equals("partial")).put("chats",rows);
                    states.incrementAndGet();body=data.toString();
                }else if(path.endsWith("/stream")&&(current.equals("live")||current.equals("native"))){
                    mime="text/event-stream";data.put("chat",chat).put("online",true).put("stream_cursor",1)
                        .put("revision",1).put("native_thread",JSONObject.NULL).put("title","隔离测试")
                        .put("native_unavailable",current.equals("native")).put("turns",new JSONArray().put(
                            new JSONObject().put("id",turn).put("status","done").put("created",1)
                            .put("prompt","测试问题").put("answer","保留的回答")));
                    body="id: 1\nevent: snapshot\ndata: "+data+"\n\n";
                }else if(path.endsWith("/stream")){
                    status=current.equals("503")?503:404;
                    data.put("error","路径不存在");
                    if(current.equals("deleted"))data.put("code","chat_deleted").put("chat",chat);
                    errors.incrementAndGet();body=data.toString();
                }else{
                    if(path.equals("/api/jobs"))jobs.incrementAndGet();status=404;body="{}";
                }
                OutputStream out=s.getOutputStream();byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
                String length=mime.equals("text/event-stream")?"":"Content-Length: "+bytes.length+"\r\n";
                out.write(("HTTP/1.1 "+status+" Test\r\nContent-Type: "+mime+"\r\n"+length+"Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(bytes);out.flush();
                if(mime.equals("text/event-stream")){
                    while(!server.isClosed()&&current.equals(mode))Thread.sleep(80);
                }
            }catch(Exception ignored){}
        }
        public void close()throws IOException{server.close();}
    }
}
