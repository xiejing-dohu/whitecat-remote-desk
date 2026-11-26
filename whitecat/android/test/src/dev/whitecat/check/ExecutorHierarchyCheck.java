package dev.whitecat.check;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.*;

final class ExecutorHierarchyCheck {
    private static final String SECOND = "worker-document";
    private static final String DOC_NAME = "文档执行节点";

    static void run(DeviceCheck c, Bundle result) throws Exception {
        Context context = c.getTargetContext();
        String key = "executor-hierarchy-test." + UUID.randomUUID();
        SharedPreferences prefs = context.getSharedPreferences(key, Context.MODE_PRIVATE);
        try (Station station = new Station()) {
            prefs.edit().putInt("interval", 350).commit();
            try {
                RemoteDeletionCheck.mainThread(c, () -> {
                    RemoteDeletionCheck.invoke(c.activity, "stopFeed");
                    RemoteDeletionCheck.set(c.activity, "base", station.base());
                    RemoteDeletionCheck.set(c.activity, "prefs", prefs);
                    RemoteDeletionCheck.set(c.activity, "token", "fixture");
                    RemoteDeletionCheck.invoke(c.activity, "restoreProfile");
                    java.lang.reflect.Field field = c.activity.getClass().getDeclaredField("dashboard");
                    field.setAccessible(true);
                    RemoteDeletionCheck.invoke(field.get(c.activity), "show");
                });
                c.await("home_recent", "旧版对话", 12);
                excludes(c, "home_recent", "文档一", "文档二");
                c.imageOfPage("23-home.png");
                c.press("nav_account");c.await("configure","",6);
                c.press("nav_agents");c.press("nav_transfer");c.press("nav_account");c.press("back");
                if(c.view("nav_transfer")==null || !c.view("nav_transfer").isSelected())throw new AssertionError("Settings lost the migration tab");
                c.press("nav_home");
                RemoteDeletionCheck.mainThread(c, () -> {
                    if (tag(c, "home_server").isClickable()) throw new AssertionError("Home server is actionable");
                });
                click(c, "home_agents");
                tag(c, "executor_" + SECOND);
                c.imageOfPage("22-executors.png");
                click(c, "executor_" + SECOND);
                c.await("list_rows", "文档一", 10);
                c.await("list_rows", "文档二", 10);
                excludes(c, "list_rows", "旧版对话", "远程已删除");
                c.press("new_chat");
                c.fill("message", "secondary-draft");
                c.press("list_chats");
                c.press("nav_agents");
                click(c, "executor_default");
                c.await("list_rows", "旧版对话", 10);
                excludes(c, "list_rows", "文档一", "文档二");
                c.press("new_chat");
                excludes(c, "message", "secondary-draft");
                c.fill("message", "default-draft");
                c.press("list_chats");
                c.press("nav_agents");
                click(c, "executor_" + SECOND);
                c.press("nav_home");
                c.await("home_recent", "文档一", 10);
                excludes(c, "home_recent", "旧版对话");
                c.press("new_chat");
                c.await("message", "secondary-draft", 8);
                RemoteDeletionCheck.mainThread(c, () -> {
                    if (!c.contents(tag(c, "chat_executor")).contains(DOC_NAME)) throw new AssertionError("Wrong composer owner");
                });
                c.press("send");
                c.await("transcript", "fixture answer", 12);
                if (station.requests.size() != 1) throw new AssertionError("New request count " + station.requests.size());
                JSONObject first = station.requests.get(0);
                if (!SECOND.equals(first.optString("executor")) || !"codex".equals(first.optString("agent")) || !first.isNull("chat"))
                    throw new AssertionError("New chat routing lost role or executor");
                c.fill("message", "follow-up");
                c.press("send");
                long end = SystemClock.elapsedRealtime() + 10000;
                while (station.requests.size() < 2 && SystemClock.elapsedRealtime() < end) SystemClock.sleep(80);
                if (station.requests.size() != 2 || !station.created.equals(station.requests.get(1).optString("chat")))
                    throw new AssertionError("Follow-up changed chat");
                while (prefs.contains("pending") && SystemClock.elapsedRealtime() < end) SystemClock.sleep(60);
                if (prefs.contains("pending")) throw new AssertionError("Follow-up receipt missing");
                c.await("transcript", "fixture answer", 10);
                c.press("list_chats");
                c.await("list_rows", "新增文档会话", 10);
                JSONObject transfer = (JSONObject)RemoteDeletionCheck.migration("snapshot",
                        new Class<?>[]{SharedPreferences.class, boolean.class, boolean.class, boolean.class, String.class},
                        prefs, true, true, false, null);
                JSONArray index = transfer.getJSONArray("index");
                int owners = 0;
                for (int i=0; i<index.length(); i++) {
                    JSONObject chat = index.getJSONObject(i);
                    if (chat.has("remote_state")) throw new AssertionError("Executor filtering marked a chat unavailable");
                    if (SECOND.equals(chat.optString("executor")) && DOC_NAME.equals(chat.optString("executor_name"))) owners++;
                }
                if (owners != 3 || index.length() != 4) throw new AssertionError("Transfer lost executor bindings");
                if (!prefs.getString("draft.", "").contains("default-draft")) throw new AssertionError("Other executor draft removed");
                result.putString("executor_hierarchy", "legacy default + 2 document chats, home/list filters, separate drafts, new task + follow-up and transfer");
            } finally {
                RemoteDeletionCheck.mainThread(c, () -> {
                    RemoteDeletionCheck.invoke(c.activity, "stopFeed");
                    c.activity.finish();
                });
                context.deleteSharedPreferences(key);
            }
        }
    }

    private static View tag(DeviceCheck c, String key) {
        return c.activity.findViewById(android.R.id.content).findViewWithTag(key);
    }

    private static void click(DeviceCheck c, String key) {
        long timeout = SystemClock.elapsedRealtime() + 10000;
        do {
            boolean[] ok = {false};
            c.runOnMainSync(() -> {
                View found = tag(c, key);
                ok[0] = found != null && found.isShown() && found.performClick();
            });
            if (ok[0]) return;
            SystemClock.sleep(80);
        } while (SystemClock.elapsedRealtime() < timeout);
        throw new AssertionError("Missing " + key);
    }

    private static void excludes(DeviceCheck c, String view, String... forbidden) {
        RemoteDeletionCheck.mainThread(c, () -> {
            String visible = c.contents(c.view(view));
            for (String text : forbidden) if (visible.contains(text)) throw new AssertionError(view + " displayed " + text);
        });
    }

    private static final class Station implements AutoCloseable {
        final String legacy=id(), doc1=id(), doc2=id(), created=id(), instance=id(), turn=id();
        final CopyOnWriteArrayList<JSONObject> requests = new CopyOnWriteArrayList<>();
        final ServerSocket listener = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));

        Station() throws IOException {
            Thread server = new Thread(() -> {
                while (!listener.isClosed()) {
                    try {
                        Socket peer = listener.accept();
                        Thread thread = new Thread(() -> reply(peer));
                        thread.setDaemon(true);
                        thread.start();
                    } catch (IOException closed) { return; }
                }
            });
            server.setDaemon(true);
            server.start();
        }

        String base() { return "http://127.0.0.1:" + listener.getLocalPort(); }
        static String id() { return UUID.randomUUID().toString(); }

        JSONObject chat(String id, String title, boolean document) throws JSONException {
            JSONObject item = new JSONObject().put("chat", id).put("title", title).put("created", 2).put("status", "done");
            if (document) item.put("executor", SECOND).put("executor_name", DOC_NAME);
            return item;
        }

        void reply(Socket peer) {
            try (Socket socket = peer) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                String path = reader.readLine().split(" ")[1], line;
                int len = 0;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    if (line.regionMatches(true, 0, "Content-Length:", 0, 15)) len = Integer.parseInt(line.substring(15).trim());
                }
                int code = 200;
                String mime = "application/json", body;
                if (path.equals("/api/state")) {
                    JSONArray chats = new JSONArray().put(chat(legacy, "旧版对话", false)).put(chat(doc1, "文档一",true)).put(chat(doc2, "文档二",true));
                    if (!requests.isEmpty()) chats.put(chat(created, "新增文档会话", true));
                    JSONArray workers = new JSONArray().put(new JSONObject().put("id","default").put("name","默认节点").put("online",true))
                            .put(new JSONObject().put("id",SECOND).put("name",DOC_NAME).put("online",true));
                    body = new JSONObject().put("online",true).put("instance",instance).put("chats_complete",true)
                            .put("chats",chats).put("executors",workers).toString();
                } else if (path.equals("/api/jobs")) {
                    char[] content = new char[len];
                    for (int at=0; at<len;) {
                        int n=reader.read(content,at,len-at);
                        if (n<0) throw new EOFException();
                        at+=n;
                    }
                    requests.add(new JSONObject(new String(content)));
                    body = new JSONObject().put("chat",created).put("job",turn).toString();
                } else if (path.equals("/api/chats/" + created + "/stream")) {
                    mime = "text/event-stream";
                    JSONObject row = new JSONObject().put("id",turn).put("status","done").put("prompt","fixture")
                            .put("answer","fixture answer").put("created",1);
                    JSONObject snapshot = chat(created,"新增文档会话",true).put("instance",instance).put("online",true)
                            .put("revision",requests.size()).put("native_thread",turn).put("stream_cursor",requests.size())
                            .put("turns",new JSONArray().put(row));
                    body = "id: " + requests.size() + "\nevent: snapshot\ndata: " + snapshot + "\n\n";
                } else { code = 404; body = "{}"; }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                OutputStream output = socket.getOutputStream();
                boolean stream = mime.equals("text/event-stream");
                String length = stream ? "" : "Content-Length: " + bytes.length + "\r\n";
                output.write(("HTTP/1.1 " + code + " Fixture\r\nContent-Type: " + mime + "\r\n" + length
                        + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                output.write(bytes);
                output.flush();
                if (stream) { socket.setSoTimeout(6000); reader.read(); }
            } catch (IOException | JSONException ignored) { /* Cancelled request or fixture shutdown. */ }
        }

        public void close() throws IOException { listener.close(); }
    }
}
