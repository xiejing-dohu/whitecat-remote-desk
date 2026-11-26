package dev.whitecat.desk;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The directory stores names and preference-file references. Credentials and content stay in their original profiles. */
final class ServerProfiles {
    static final String USB = "http://127.0.0.1:18744";
    static final String DIRECTORY = "servers_v1";
    private final Context context;
    private final SharedPreferences connection;
    final LinkedHashMap<String, Server> entries = new LinkedHashMap<>();

    static final class Server {
        final String url, file;
        String name;
        Server(String url, String file, String name) { this.url=url; this.file=file; this.name=name; }
    }

    ServerProfiles(Context context) {
        this.context=context;
        connection=context.getSharedPreferences("connection",0);
        if (connection.contains(DIRECTORY)) {
            try {
                JSONArray rows=new JSONArray(connection.getString(DIRECTORY,"[]"));
                for (int i=0;i<rows.length();i++) {
                    JSONObject row=rows.getJSONObject(i);
                    String url=canonical(row.getString("url")), file=row.getString("file");
                    if (validFile(file)) entries.put(url,new Server(url,file,row.getString("name")));
                }
            } catch (Exception ignored) { entries.clear(); }
        }
        if (entries.isEmpty()) importExisting();
    }

    static String canonical(String raw) throws Exception {
        URI uri=new URI(Api.endpoint(raw));
        int port=uri.getPort();
        if (port==0 || port>65535 || new URI(raw.trim()).getRawAuthority().endsWith(":"))
            throw new IOException("无效端口");
        String scheme=uri.getScheme().toLowerCase(Locale.ROOT), host=uri.getHost().toLowerCase(Locale.ROOT);
        if ("http".equals(scheme) && "localhost".equals(host)) host="127.0.0.1";
        if ((scheme.equals("https") && port==443) || (scheme.equals("http") && port==80)) port=-1;
        return new URI(scheme,null,host,port,null,null,null).toASCIIString();
    }

    private static String knownUrl(String raw) {
        try { return canonical(raw); } catch (Exception invalid) { return ""; }
    }

    private static String hashedFile(String raw) {
        return "desk."+UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean validFile(String file) {
        return file.equals("desk") || file.matches("desk\\.[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    static String defaultName(String url) {
        URI uri=URI.create(url);
        return USB.equals(url) ? "本机 USB 工作站" : uri.getAuthority();
    }

    private void importFile(String file, String fallback) {
        String raw=context.getSharedPreferences(file,0).getString("base",fallback);
        String url=knownUrl(raw);
        if (!url.isEmpty() && !entries.containsKey(url))
            entries.put(url,new Server(url,file,defaultName(url)));
    }

    private void importExisting() {
        String first=context.getSharedPreferences("desk",0).getString("base",USB);
        String active=connection.getString("active",first);
        // In older versions the original address used desk, all subsequent URLs used their raw URL hash.
        if (!active.isEmpty() && !active.equals(first)) importFile(hashedFile(active),"");
        importFile("desk",USB);
        File dir=new File(context.getApplicationInfo().dataDir,"shared_prefs");
        String[] files=dir.list();
        if (files!=null) for (String xml : files) {
            if (!xml.endsWith(".xml")) continue;
            String name=xml.substring(0,xml.length()-4);
            if (name.startsWith("desk.") && validFile(name)) importFile(name,"");
        }
        if(entries.isEmpty()) entries.put(USB,new Server(USB,hashedFile(USB),defaultName(USB)));
        save();
    }

    String active() {
        String url=knownUrl(connection.getString("active",""));
        return entries.containsKey(url) ? url : entries.values().iterator().next().url;
    }

    SharedPreferences profile(String url) {
        Server server=entries.get(url);
        if (server==null) throw new IllegalArgumentException("未知服务器");
        return context.getSharedPreferences(server.file,0);
    }

    String name(String url) {
        Server server=entries.get(url);
        return server==null ? defaultName(url) : server.name;
    }

    boolean paired(String url) {
        SharedPreferences profile=profile(url);
        return !profile.getBoolean("pair_required",false) && !profile.getString("access","").isEmpty();
    }

    Server add(String url, String name) {
        Server existing=entries.get(url);
        if (existing!=null) return existing;
        Server server=new Server(url,hashedFile(url),name.isEmpty()?defaultName(url):name);
        entries.put(url,server);
        if (!save()) { entries.remove(url); throw new IllegalStateException("服务器信息保存失败"); }
        return server;
    }

    boolean rename(String url,String name) {
        Server server=entries.get(url);
        String previous=server.name;
        server.name=name;
        if (save()) return true;
        server.name=previous;
        return false;
    }

    boolean activate(String url) {
        return entries.containsKey(url) && connection.edit().putString("active",url).commit();
    }

    private boolean save() {
        JSONArray rows=new JSONArray();
        try {
            for (Server item:entries.values())
                rows.put(new JSONObject().put("url",item.url).put("file",item.file).put("name",item.name));
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        return connection.edit().putString(DIRECTORY,rows.toString()).commit();
    }
}
