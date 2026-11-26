package dev.whitecat.desk;

import java.util.*;
import org.json.*;

final class RemoteHistory {
    static final String DELETED="chat_deleted", NATIVE="native_unavailable", UNKNOWN="unverified";
    static String label(String state){
        if(DELETED.equals(state))return "远程已删除";
        if(NATIVE.equals(state))return "原生历史不可用";
        if(UNKNOWN.equals(state))return "工作站待核实";
        return "";
    }
    static String id(JSONObject row,String field){
        String value=row.optString(field,"");
        return MigrationData.id(value)?value:"";
    }
    static JSONObject find(JSONArray rows,String chat){
        for(int n=0;n<rows.length();n++){
            JSONObject row=rows.optJSONObject(n);
            if(row!=null&&chat.equals(row.optString("chat")))return row;
        }
        return null;
    }
    static boolean nativeUnavailable(JSONObject row){
        return row.optBoolean(NATIVE,false)||row.optInt(NATIVE,0)==1;
    }
    static String evidence(JSONObject row,JSONObject response,String chat){
        String code=response.optString("code"),source=id(response,"instance");
        if(source.isEmpty()||!chat.equals(id(response,"chat"))||row==null)return "";
        if(!DELETED.equals(code)&&!NATIVE.equals(code)&&!"chat_missing".equals(code)&&!"station_changed".equals(code))return "";
        String prior=row.optString("remote_instance");
        if("station_changed".equals(code))return UNKNOWN;
        if(!prior.isEmpty()&&!prior.equals(source))return UNKNOWN;
        if(DELETED.equals(code)||NATIVE.equals(code))return code;
        if("chat_missing".equals(code))return prior.equals(source)?DELETED:UNKNOWN;
        return "";
    }
    static JSONArray reconcile(JSONArray previous,JSONObject response)throws JSONException{
        JSONArray incoming=response.getJSONArray("chats");
        String source=id(response,"instance");
        boolean complete=!source.isEmpty()&&Boolean.TRUE.equals(response.opt("chats_complete"));
        Set<String> present=new HashSet<>(),removed=new HashSet<>();
        JSONArray tombstones=response.optJSONArray("deleted_chats");
        for(int n=0;tombstones!=null&&n<tombstones.length();n++)removed.add(tombstones.optString(n));
        ArrayList<JSONObject> result=new ArrayList<>();
        for(int n=0;n<incoming.length();n++){
            JSONObject row=incoming.getJSONObject(n);
            String chat=id(row,"chat");
            if(chat.isEmpty()||!present.add(chat))throw new JSONException("无效会话目录");
            row.remove("remote_state");row.remove("remote_instance");
            if(!source.isEmpty())row.put("remote_instance",source);
            if(nativeUnavailable(row))row.put("remote_state",NATIVE);
            result.add(row);
        }
        for(int n=0;n<previous.length();n++){
            JSONObject old=previous.optJSONObject(n);
            if(old==null||present.contains(old.optString("chat")))continue;
            JSONObject row=new JSONObject(old.toString());
            String prior=row.optString("remote_instance"),chat=row.optString("chat");
            if(complete && (source.equals(prior)||(prior.isEmpty()&&removed.contains(chat)))){
                row.put("remote_state",DELETED).put("remote_instance",source);
            }else if(complete && (!source.equals(prior)||!row.has("remote_state"))){
                row.put("remote_state",UNKNOWN);
            }
            result.add(row);
        }
        Collections.sort(result,(one,two)->Double.compare(two.optDouble("created",0),one.optDouble("created",0)));
        return new JSONArray(result);
    }
    private RemoteHistory(){}
}
