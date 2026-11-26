package dev.whitecat.desk;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

final class Api {
    static String endpoint(String input) throws Exception {
        URI u = new URI(input.trim());
        String scheme = u.getScheme(), host = u.getHost();
        boolean local = "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
        if (host == null || !("https".equalsIgnoreCase(scheme) || (local && "http".equalsIgnoreCase(scheme)))
                || u.getRawUserInfo()!=null || u.getRawQuery()!=null || u.getRawFragment()!=null
                || !(u.getPath().isEmpty() || "/".equals(u.getPath())))
            throw new IOException("请输入 HTTPS 服务地址，或使用 USB 本机通道");
        return new URI(scheme,null,host,u.getPort(),null,null,null).toASCIIString();
    }

    static class Failure extends IOException {
        final int status;
        final JSONObject body;
        Failure(int code, String message, JSONObject body){super(message);status=code;this.body=body;}
    }

    static JSONObject call(String base,String token,String path,JSONObject payload) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(base+path).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(6000); c.setReadTimeout(18000); c.setUseCaches(false);
        if (!token.isEmpty()) c.setRequestProperty("Authorization","Bearer "+token);
        try {
            if (payload!=null) {
                c.setRequestMethod("POST"); c.setDoOutput(true);
                byte[] data=payload.toString().getBytes(StandardCharsets.UTF_8);
                c.setRequestProperty("Content-Type","application/json"); c.setFixedLengthStreamingMode(data.length);
                try(OutputStream out=c.getOutputStream()){out.write(data);}
            }
            int status=c.getResponseCode();
            boolean ok=status>=200 && status<300;
            InputStream source=ok?c.getInputStream():c.getErrorStream();
            String text=source==null?"":read(source);
            if (!ok) {
                String message="服务返回 HTTP "+status;
                JSONObject error=new JSONObject();
                try {error=new JSONObject(text);message=error.optString("error",message);} catch(JSONException ignored){}
                throw new Failure(status,message,error);
            }
            return new JSONObject(text);
        } finally {c.disconnect();}
    }

    static String read(InputStream source) throws IOException {
        try(InputStream in=source; ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buf=new byte[8192]; int n;
            while ((n=in.read(buf))!=-1) out.write(buf,0,n);
            return out.toString("UTF-8");
        }
    }
}
