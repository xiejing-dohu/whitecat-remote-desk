package dev.whitecat.desk;

import android.os.Handler;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;

/** A single authenticated, resumable subscription for the visible chat. */
final class ChatFeed extends Thread {
    interface Listener {
        boolean frame(String event, JSONObject data) throws Exception;
        void offline(boolean unauthorized);
    }
    private final String url,token;
    private final Handler ui;
    private final Listener listener;
    private volatile boolean stopped;
    private volatile HttpURLConnection connection;
    private long cursor;

    ChatFeed(String base,String chat,String token,long cursor,Handler ui,Listener listener){
        super("whitecat-chat-stream");this.url=base+"/api/chats/"+chat+"/stream";
        this.token=token;this.cursor=cursor;this.ui=ui;this.listener=listener;setDaemon(true);
    }
    void cancel(){
        stopped=true;interrupt();
        final HttpURLConnection c=connection;
        if(c!=null)new Thread(c::disconnect,"whitecat-close-stream").start();
    }
    boolean dispatch(String kind,JSONObject payload)throws InterruptedException{
        CountDownLatch done=new CountDownLatch(1);boolean[] ok={false};
        ui.post(()->{try{if(!stopped)ok[0]=listener.frame(kind,payload);}catch(Exception ignored){}
                    finally{done.countDown();}});
        done.await();return ok[0];
    }
    public void run(){
        int delay=250;
        while(!stopped){
            boolean unauthorized=false;
            try{
                HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();connection=c;
                c.setInstanceFollowRedirects(false);c.setUseCaches(false);c.setConnectTimeout(4000);c.setReadTimeout(6500);
                c.setRequestProperty("Accept","text/event-stream");c.setRequestProperty("Authorization","Bearer "+token);
                if(cursor>=0)c.setRequestProperty("Last-Event-ID",Long.toString(cursor));
                int code=c.getResponseCode();unauthorized=code==401;
                if(code==404||code==410){
                    JSONObject error=new JSONObject(Api.read(c.getErrorStream()));
                    if("chat_missing".equals(error.optString("code"))||"chat_deleted".equals(error.optString("code"))){
                        // A proxy's HTTP status alone never establishes loss of a conversation.
                        if(dispatch("unavailable",error))return;
                    }
                }
                if(code!=200)throw new IOException("Stream HTTP "+code);
                delay=250;
                try(BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8))){
                    String line,event="",data="";long id=-1;
                    while(!stopped&&(line=r.readLine())!=null){
                        if(line.startsWith("id: "))id=Long.parseLong(line.substring(4));
                        else if(line.startsWith("event: "))event=line.substring(7);
                        else if(line.startsWith("data: "))data=line.substring(6);
                        else if(line.isEmpty()&&!data.isEmpty()){
                            JSONObject payload=new JSONObject(data);String kind=event;
                            if(!dispatch(kind,payload))throw new IOException("Frame was not cached");
                            if("unavailable".equals(kind))return;
                            cursor=id;event="";data="";id=-1;
                        }
                    }
                }
            }catch(InterruptedException e){return;}
            catch(Exception e){/* Retry the last frame committed by the Activity. */}
            finally{HttpURLConnection c=connection;connection=null;if(c!=null)c.disconnect();}
            if(stopped)return;
            final boolean auth=unauthorized;
            ui.post(()->{if(!stopped)listener.offline(auth);});if(auth)return;
            try{Thread.sleep(delay);}catch(InterruptedException e){return;}
            delay=Math.min(delay*2,3000);
        }
    }
}
