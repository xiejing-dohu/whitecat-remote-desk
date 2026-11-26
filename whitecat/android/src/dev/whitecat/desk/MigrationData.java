package dev.whitecat.desk;

import android.content.SharedPreferences;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.zip.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;

/** Portable, explicitly selected content of one workstation profile. */
final class MigrationData {
    static final String PREFIX = "whitecat:migrate:1:";
    static final int MAX_PLAIN = 8 * 1024 * 1024, MAX_BOX = 4 * 1024 * 1024;
    static final int URL64 = Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING;
    static final SecureRandom RANDOM = new SecureRandom();
    static final String UUID_PATTERN = "[a-fA-F0-9]{8}-(?:[a-fA-F0-9]{4}-){3}[a-fA-F0-9]{12}";
    static boolean id(String value) { return value.matches(UUID_PATTERN); }
    static boolean agent(String value) { return id(value) || "codex".equals(value); }
    static boolean draftSlot(String value) {
        return value.isEmpty() || id(value) || (value.startsWith("executor.") && value.length() > 9);
    }
    static boolean hidden(SharedPreferences p, String id) { return !id.isEmpty() && p.getBoolean("hidden."+id,false); }
    static JSONObject local(SharedPreferences p,String key) {
        try {return new JSONObject(p.getString(key,"{}"));} catch(Exception e){return new JSONObject();}
    }
    static JSONArray index(SharedPreferences p) {
        try {return new JSONArray(p.getString("index","[]"));} catch(Exception e){return new JSONArray();}
    }

    // Keep only fields the app displays or needs for stream continuity.
    static JSONObject fields(JSONObject src,String names) throws JSONException {
        JSONObject dst=new JSONObject();
        for(String name:names.split(" ")){
            Object v=src.opt(name);
            if(v instanceof String || v instanceof Number || v instanceof Boolean || v==JSONObject.NULL) dst.put(name,v);
        }
        return dst;
    }
    static JSONArray rows(JSONObject parent,String name,String keys) throws JSONException {
        JSONArray input=parent.optJSONArray(name), out=new JSONArray();
        for(int i=0;input!=null && i<input.length();i++){
            JSONObject row=input.optJSONObject(i);
            if(row!=null) out.put(fields(row,keys));
        }
        return out;
    }
    static JSONObject indexEntry(JSONObject src)throws JSONException{
        JSONObject row=fields(src,"chat title preview created status agent agent_name executor executor_name");
        String source=RemoteHistory.id(src,"remote_instance"),state=src.optString("remote_state");
        if(!source.isEmpty())row.put("remote_instance",source);
        if(!RemoteHistory.label(state).isEmpty()&&(!source.isEmpty()||RemoteHistory.UNKNOWN.equals(state)))
            row.put("remote_state",state);
        return row;
    }
    static boolean differentSources(JSONObject left,JSONObject right,String field){
        String a=RemoteHistory.id(left,field),b=RemoteHistory.id(right,field);
        return !a.isEmpty()&&!b.isEmpty()&&!a.equals(b);
    }
    static JSONObject history(JSONObject src) throws JSONException {
        JSONObject result=fields(src,"title revision stream_cursor native_thread agent agent_name executor executor_name");
        for(String field:new String[]{"chat","instance"})if(id(src.optString(field)))result.put(field,src.getString(field));
        if(src.opt("native_unavailable") instanceof Boolean)result.put("native_unavailable",src.getBoolean("native_unavailable"));
        JSONArray turns=src.getJSONArray("turns"), out=new JSONArray();
        for(int i=0;i<turns.length();i++){
            JSONObject original=turns.getJSONObject(i);
            JSONObject row=fields(original,"id prompt filename status answer created agent agent_name executor executor_name");
            row.getString("id");
            row.put("events",rows(original,"events","id label at"));
            if(original.has("parts")) row.put("parts",rows(original,"parts","job item kind body status progress label started_ms ended_ms"));
            if(original.has("followups")){
                JSONArray follow=original.getJSONArray("followups"), text=new JSONArray();
                for(int j=0;j<follow.length();j++) if(follow.opt(j) instanceof String) text.put(follow.getString(j));
                row.put("followups",text);
            }
            out.put(row);
        }
        return result.put("turns",out);
    }
    static boolean filled(JSONObject draft) {
        return !draft.optString("prompt").isEmpty() || !draft.optString("filename").isEmpty() || !draft.optString("sample").isEmpty();
    }
    static JSONObject snapshot(SharedPreferences p,boolean chats,boolean drafts,boolean settings,String only) throws Exception {
        JSONObject payload=new JSONObject().put("version",1);
        if(chats){
            JSONArray visible=new JSONArray(), list=index(p);
            for(int i=0;i<list.length();i++){
                JSONObject row=list.optJSONObject(i);
                if(row!=null && id(row.optString("chat")) && !hidden(p,row.optString("chat")) && (only==null||only.equals(row.optString("chat"))))
                    visible.put(indexEntry(row));
            }
            payload.put("index",visible);
        }
        JSONObject docs=new JSONObject(), saved=new JSONObject();
        for(String key:p.getAll().keySet()){
            if(chats && key.startsWith("cache.")){
                String id=key.substring(6);
                if(id(id) && !hidden(p,id) && (only==null||only.equals(id))){
                    JSONObject doc=local(p,key);
                    if(doc.optJSONArray("turns")!=null) docs.put(id,history(doc));
                }
            }
            if(drafts && key.startsWith("draft.")){
                String id=key.substring(6);
                if(draftSlot(id) && !hidden(p,id) && (only==null||only.equals(id))){
                    JSONObject draft=fields(local(p,key),"prompt filename sample");
                    if(filled(draft)) saved.put(id,draft);
                }
            }
        }
        if(chats) payload.put("history",docs);
        if(drafts) payload.put("drafts",saved);
        if(settings){
            JSONObject prefs=new JSONObject().put("interval",p.getInt("interval",2000)).put("enterSends",p.getBoolean("enterSends",false));
            String ref=p.getString("selected_agent","");
            if(agent(ref)){
                prefs.put("selected_agent",ref);
                if(p.contains("selected_agent_name"))prefs.put("selected_agent_name",p.getString("selected_agent_name",""));
            }
            payload.put("settings",prefs);
        }
        return payload;
    }

    static int count(JSONObject root,String key){JSONObject value=root.optJSONObject(key);return value==null?0:value.length();}
    static String summary(JSONObject root){
        if(!root.has("history") && !root.has("drafts") && !root.has("settings")) return "没有可导入的数据";
        List<String> parts=new ArrayList<>();
        if(root.has("history")){
            JSONArray list=root.optJSONArray("index");
            parts.add("会话索引 "+(list==null?0:list.length())+" 条，缓存记录 "+count(root,"history")+" 份");
        }
        if(root.has("drafts")) parts.add("草稿 "+count(root,"drafts")+" 份");
        if(root.has("settings")) parts.add("设置及聊天角色引用");
        return android.text.TextUtils.join(" · ",parts);
    }

    static void merge(SharedPreferences p,JSONObject data,String receipt) throws Exception {
        if(data.getInt("version")!=1) throw new IOException("迁移数据版本不兼容");
        SharedPreferences.Editor edit=p.edit();
        JSONArray incoming=data.optJSONArray("index"), existing=index(p);
        HashSet<String> otherStation=new HashSet<>();
        if(incoming!=null){
            LinkedHashMap<String,JSONObject> combined=new LinkedHashMap<>();
            for(int n=0;n<existing.length();n++){
                JSONObject row=existing.optJSONObject(n);
                if(row!=null && id(row.optString("chat")) && !hidden(p,row.optString("chat"))) combined.put(row.getString("chat"),row);
            }
            for(int n=0;n<incoming.length();n++){
                JSONObject row=incoming.getJSONObject(n);
                String id=row.getString("chat");
                if(!id(id)||hidden(p,id))continue;
                JSONObject imported=indexEntry(row),previous=combined.get(id);
                if(previous==null){combined.put(id,imported);continue;}
                if(differentSources(previous,imported,"remote_instance")){otherStation.add(id);continue;}
                JSONObject latest=imported.optDouble("created",0)>previous.optDouble("created",0)?imported:previous;
                // Migration is an offline copy. A subsequent station response can clear an old deletion.
                JSONObject authority=latest;
                if(RemoteHistory.DELETED.equals(previous.optString("remote_state")))authority=previous;
                else if(RemoteHistory.DELETED.equals(imported.optString("remote_state")))authority=imported;
                else if(!latest.has("remote_instance")||(!latest.has("remote_state")&&imported.has("remote_state")))authority=imported.has("remote_instance")?imported:previous;
                if(authority.has("remote_instance"))latest.put("remote_instance",authority.get("remote_instance"));
                if(authority.has("remote_state"))latest.put("remote_state",authority.get("remote_state"));
                JSONObject binding=previous.has("executor")?previous:imported;
                if(binding.has("executor")){
                    latest.put("executor",binding.get("executor"));
                    if(binding.has("executor_name"))latest.put("executor_name",binding.get("executor_name"));
                }
                combined.put(id,latest);
            }
            ArrayList<JSONObject> ordered=new ArrayList<>(combined.values());
            Collections.sort(ordered,(a,b)->Double.compare(b.optDouble("created",0),a.optDouble("created",0)));
            edit.putString("index",new JSONArray(ordered).toString());
        }
        JSONObject docs=data.optJSONObject("history"), drafts=data.optJSONObject("drafts");
        if(docs!=null) for(Iterator<String> it=docs.keys();it.hasNext();){
            String id=it.next(); if(!id(id)||hidden(p,id)||otherStation.contains(id))continue;
            String key="cache."+id;
            JSONObject src=docs.getJSONObject(id), dst=local(p,key);
            if(differentSources(src,dst,"instance")){otherStation.add(id);continue;}
            // Compare server-issued revisions whenever the local snapshot has a predecessor.
            if(!p.contains(key) || (src.has("stream_cursor") && dst.has("stream_cursor")
                && src.getLong("stream_cursor")>dst.getLong("stream_cursor") && src.optLong("revision",-1)>=dst.optLong("revision",-1))
                || (!dst.has("stream_cursor") && src.optLong("revision",-1)>dst.optLong("revision",-1)))
                edit.putString(key,history(src).toString());
        }
        if(drafts!=null) for(Iterator<String> it=drafts.keys();it.hasNext();){
            String id=it.next(); if(!draftSlot(id)||hidden(p,id)||otherStation.contains(id))continue;
            String key="draft."+id;
            JSONObject draft=fields(drafts.getJSONObject(id),"prompt filename sample");
            if(filled(draft) && !p.contains(key)) edit.putString(key,draft.toString());
        }
        JSONObject opts=data.optJSONObject("settings");
        if(opts!=null){
            int ms=opts.optInt("interval",-1);
            if(!p.contains("interval")&&(ms==2000||ms==5000||ms==15000)) edit.putInt("interval",ms);
            if(!p.contains("enterSends")&&opts.opt("enterSends") instanceof Boolean) edit.putBoolean("enterSends",opts.getBoolean("enterSends"));
            String ref=opts.optString("selected_agent","");
            if(!p.contains("selected_agent")&&agent(ref)){
                edit.putString("selected_agent",ref);
                if(opts.opt("selected_agent_name") instanceof String)edit.putString("selected_agent_name",opts.getString("selected_agent_name"));
            }
        }
        if(!edit.putString("migration.receipt",receipt).commit())throw new IOException("保存导入数据失败");
    }

    static byte[] random(int size){byte[] bytes=new byte[size];RANDOM.nextBytes(bytes);return bytes;}
    static Cipher cipher(int mode,byte[] key,byte[] iv) throws Exception {
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
        c.updateAAD(PREFIX.getBytes(StandardCharsets.US_ASCII));
        return c;
    }
    static String encrypt(JSONObject payload,byte[] key) throws Exception {
        byte[] plain=payload.toString().getBytes(StandardCharsets.UTF_8);
        if(plain.length>MAX_PLAIN) throw new IOException("所选数据超过 8 MB，请减少迁移范围");
        ByteArrayOutputStream packed=new ByteArrayOutputStream();
        try(GZIPOutputStream zip=new GZIPOutputStream(packed)){zip.write(plain);}
        byte[] iv=random(12), ciphertext=cipher(Cipher.ENCRYPT_MODE,key,iv).doFinal(packed.toByteArray());
        if(iv.length+ciphertext.length>MAX_BOX) throw new IOException("压缩后数据超过 4 MB，请减少迁移范围");
        ByteArrayOutputStream box=new ByteArrayOutputStream();box.write(iv);box.write(ciphertext);
        return Base64.encodeToString(box.toByteArray(),Base64.NO_WRAP);
    }
    static JSONObject decrypt(String encoded,byte[] key) throws Exception {
        if(encoded.length()>((MAX_BOX+2)/3)*4)throw new IOException();
        byte[] wrapped=Base64.decode(encoded,Base64.NO_WRAP);
        if(wrapped.length<28||wrapped.length>MAX_BOX)throw new IOException();
        byte[] plain=cipher(Cipher.DECRYPT_MODE,key,Arrays.copyOf(wrapped,12)).doFinal(wrapped,12,wrapped.length-12);
        try(InputStream zip=new GZIPInputStream(new ByteArrayInputStream(plain));ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int n;
            while((n=zip.read(buffer))!=-1){if(out.size()+n>MAX_PLAIN)throw new IOException();out.write(buffer,0,n);}
            JSONObject result=new JSONObject(out.toString("UTF-8"));
            if(result.getInt("version")!=1)throw new IOException();
            return result;
        }
    }
}
