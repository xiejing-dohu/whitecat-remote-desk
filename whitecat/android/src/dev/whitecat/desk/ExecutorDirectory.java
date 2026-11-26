package dev.whitecat.desk;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The active server owns the directory, selection and new-chat drafts. */
final class ExecutorDirectory {
    static final String DEFAULT = "default";
    private final ChatActivity a;

    ExecutorDirectory(ChatActivity activity) { a = activity; }

    String selected() { return a.prefs.getString("selected_executor", DEFAULT); }

    static String owner(JSONObject chat) {
        if (chat == null || chat.isNull("executor") || chat.optString("executor").isEmpty()) return DEFAULT;
        return chat.optString("executor");
    }

    void select(String id) {
        a.prefs.edit().putString("selected_executor", id).apply();
    }

    void open(String id) {
        if (a.waitingForReceipt()) return;
        select(id);
        a.history();
    }

    JSONArray chats(String id) {
        JSONArray visible = new JSONArray();
        for (int n = 0; n < a.chats.length(); n++) {
            JSONObject row = a.chats.optJSONObject(n);
            if (row != null && owner(row).equals(id)) visible.put(row);
        }
        return visible;
    }

    private JSONArray legacy(String name, boolean online) {
        JSONArray list = new JSONArray();
        try {
            list.put(new JSONObject().put("id", DEFAULT).put("name", name).put("online", online));
        } catch (JSONException e) { throw new IllegalStateException(e); }
        return list;
    }

    void update(JSONObject state) throws JSONException {
        JSONArray list = state.has("executors") ? state.getJSONArray("executors") :
                legacy(state.optString("station_name", "默认执行 Agent"), state.optBoolean("online"));
        JSONArray visible = new JSONArray();
        for (int i = 0; i < list.length(); i++) {
            JSONObject worker = list.getJSONObject(i);
            String id = worker.getString("id");
            if (id.isEmpty()) throw new JSONException("无效执行 Agent 编号");
            visible.put(new JSONObject().put("id", id).put("name", worker.optString("name", id)).put("online", worker.optBoolean("online")));
        }
        String json = visible.toString();
        if (!json.equals(a.prefs.getString("executors", ""))) a.prefs.edit().putString("executors", json).apply();
    }

    JSONArray all() {
        String cached = a.prefs.getString("executors", "");
        if (cached.isEmpty()) return legacy("默认执行 Agent", false);
        try { return new JSONArray(cached); } catch (JSONException e) { return new JSONArray(); }
    }

    JSONObject find(String id) {
        JSONArray list = all();
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item != null && id.equals(item.optString("id"))) return item;
        }
        return null;
    }

    String name(String id) {
        JSONObject item = find(id);
        if (item != null && !item.optString("name").isEmpty()) return item.optString("name");
        for (int i = 0; i < a.chats.length(); i++) {
            JSONObject chat = a.chats.optJSONObject(i);
            if (chat != null && owner(chat).equals(id) && !chat.optString("executor_name").isEmpty()) {
                return chat.optString("executor_name");
            }
        }
        return DEFAULT.equals(id) ? "默认执行 Agent" : id;
    }

    String currentChatOwner() {
        if (a.chat.isEmpty()) return selected();
        JSONObject row = RemoteHistory.find(a.chats, a.chat);
        if (row != null) return owner(row);
        JSONObject cache = a.jsonPref("cache." + a.chat);
        return owner(cache);
    }

    String draftKey() {
        if (!a.chat.isEmpty() || DEFAULT.equals(selected())) return "draft." + a.chat;
        return "draft.executor." + selected();
    }
}
