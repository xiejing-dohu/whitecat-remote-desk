package dev.whitecat.desk;

import android.app.AlertDialog;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.UUID;

final class AgentPage {
    private final ChatActivity a;
    private LinearLayout rows;
    private TextView feedback;
    private LinearLayout executorRows;
    private String directoryStamp;

    AgentPage(ChatActivity activity) {
        a = activity;
    }

    private void screen(String page) {
        a.page = page;
        a.generation++;
        a.frame();
    }

    private LinearLayout scrollBody() {
        ScrollView scroll = new ScrollView(a);
        a.root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout body = a.col();
        a.pad(body, 18, 12);
        scroll.addView(body);
        return body;
    }

    private TextView label(String value, int size, int color, boolean bold) {
        TextView text = a.text(value, size, color);
        if (bold) a.bold(text);
        return text;
    }

    private void setupFeedback(LinearLayout body) {
        feedback = a.text("", 14, a.muted);
        a.pad(feedback, 2, 10);
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        feedback.setVisibility(View.GONE);
        body.addView(feedback);
    }

    private void message(String value, boolean error) {
        feedback.setText(value);
        feedback.setTextColor(error ? a.amber : a.muted);
        feedback.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private Button primary(String value, Runnable click) {
        Button button = a.button(value, View.generateViewId(), click);
        button.setBackground(a.bg(a.blue, 12, false));
        a.bold(button);
        return button;
    }

    void show() {
        screen("agents");
        LinearLayout bar = a.row();
        a.pad(bar, 16, 6);
        a.root.addView(bar, a.lp(-1, -2));
        bar.addView(a.cat(), a.lp(32, 32));
        TextView brand = label("白猫", 18, a.ink, true);
        a.pad(brand, 10, 0);
        bar.addView(brand, a.weight());
        LinearLayout body = scrollBody();
        body.addView(label("已接入的 Agent", 30, a.ink, true));
        TextView server = a.text(a.activeServerName(), 16, a.secondary);
        server.setTag("executor_server");
        LinearLayout.LayoutParams gap = a.lp(-1, -2);
        gap.topMargin = a.dp(12);
        body.addView(server, gap);
        executorRows = a.col();
        a.pad(executorRows, 0, 14);
        executorRows.setTag("executor_rows");
        body.addView(executorRows);
        Button roles = a.button("聊天角色模板", View.generateViewId(), this::roles);
        roles.setTag("role_templates");
        body.addView(roles, a.lp(-1, -2));
        a.homeNav("agents");
        directoryStamp = null;
        updateDirectory();
        a.refresh();
    }

    void updateDirectory() {
        JSONArray directory = a.workers.all();
        String stamp = directory.toString() + "|" + a.workers.selected() + "|" + a.chats.toString() + "|" + a.connected;
        if (stamp.equals(directoryStamp)) return;
        directoryStamp = stamp;
        executorRows.removeAllViews();
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (int n = 0; n < directory.length(); n++) {
            JSONObject item = directory.optJSONObject(n);
            if (item == null || item.optString("id").isEmpty() || !ids.add(item.optString("id"))) continue;
            String status = !a.connected ? "状态待同步" : (item.optBoolean("online") ? "在线" : "离线");
            executorCard(item.optString("id"), item.optString("name", item.optString("id")), status);
        }
        for (int n = 0; n < a.chats.length(); n++) {
            String id = ExecutorDirectory.owner(a.chats.optJSONObject(n));
            if (ids.add(id)) executorCard(id, a.workers.name(id), "本机会话 · 接入待确认");
        }
        if (ids.isEmpty()) executorRows.addView(a.text("此服务器尚无已接入的执行 Agent。", 15, a.secondary));
    }

    private void executorCard(String id, String name, String status) {
        LinearLayout card = a.col();
        a.pad(card, 16, 13);
        card.setBackground(a.bg(a.surface, 14, false));
        card.setFocusable(true);
        card.setTag("executor_" + id);
        card.setContentDescription(name + "，" + status + "，查看会话");
        card.setOnClickListener(v -> a.workers.open(id));
        card.addView(label(name, 18, a.ink, true));
        TextView meta = a.text(status + "  ·  " + a.workers.chats(id).length() + " 个会话"
                + (id.equals(a.workers.selected()) ? "  ·  当前 Agent" : ""), 14, a.secondary);
        a.pad(meta, 0, 4);
        card.addView(meta);
        LinearLayout.LayoutParams layout = a.lp(-1, -2);
        layout.bottomMargin = a.dp(8);
        executorRows.addView(card, layout);
    }

    void roles() {
        screen("roles");
        LinearLayout bar = a.row();
        a.pad(bar, 18, 4);
        a.root.addView(bar, a.lp(-1, 56));
        bar.addView(a.icon(R.drawable.ic_back, R.id.back, "返回执行 Agent", this::show), a.lp(44, 44));
        TextView brand = label(a.workers.name(a.workers.selected()), 18, a.ink, true);
        a.pad(brand, 10, 0);
        bar.addView(brand, a.weight());
        LinearLayout body = scrollBody();
        TextView heading = label("聊天角色模板", 32, a.ink, true);
        a.pad(heading, 2, 12);
        body.addView(heading);
        body.addView(a.text("模板设定新会话的角色指令，由选定的执行 Agent 处理。", 14, a.secondary));
        body.addView(primary("添加角色模板", () -> edit(null)), a.lp(-1, -2));
        TextView section = label("可用角色模板", 13, a.secondary, true);
        section.setPadding(a.dp(2), a.dp(22), 0, a.dp(10));
        body.addView(section);
        setupFeedback(body);
        rows = a.col();
        body.addView(rows);
        a.homeNav("agents");
        refresh();
    }

    private boolean current(int screen) {
        return a.alive() && a.generation == screen;
    }

    private boolean unauthorized(Exception failure) {
        if (failure instanceof Api.Failure && ((Api.Failure)failure).status == 401) {
            a.token = "";
            a.pairPage();
            return true;
        }
        return false;
    }

    private void refresh() {
        final int screen = a.generation;
        final String origin = a.base, access = a.token;
        rows.removeAllViews();
        message("正在读取聊天角色…", false);
        a.network.execute(() -> {
            try {
                JSONArray agents = Api.call(origin, access, "/api/agents", null).getJSONArray("agents");
                a.ui.post(() -> {
                    if (!current(screen)) return;
                    message("", false);
                    for (int i=0; i<agents.length(); i++) {
                        JSONObject agent = agents.optJSONObject(i);
                        if (agent != null && !agent.optBoolean("archived")) card(agent);
                    }
                });
            } catch (Exception error) {
                a.ui.post(() -> {
                    if (!current(screen) || unauthorized(error)) return;
                    message(error instanceof Api.Failure ? error.getMessage() : "无法读取角色模板，请检查服务器连接。", true);
                    rows.addView(a.button("重新加载", View.generateViewId(), this::refresh), a.lp(-2,44));
                });
            }
        });
    }

    private void card(JSONObject agent) {
        String name = agent.optString("name");
        boolean builtin = agent.optBoolean("builtin");
        LinearLayout card = a.row();
        a.pad(card, 14, 9);
        card.setMinimumHeight(a.dp(82));
        card.setBackground(a.bg(a.surface, 14, false));
        LinearLayout.LayoutParams box = a.lp(-1, -2);
        box.bottomMargin = a.dp(10);
        rows.addView(card, box);
        TextView symbol = label(builtin ? "›_" : "✦", 17, a.secondary, true);
        symbol.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(symbol, a.lp(32, -2));
        LinearLayout words = a.col();
        card.addView(words, a.weight());
        TextView title = label(name, 17, a.ink, true);
        title.setSingleLine();
        title.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(title);
        TextView role = a.text(builtin ? "默认聊天角色" : agent.optString("instructions"), 13, a.secondary);
        role.setMaxLines(2);
        role.setEllipsize(TextUtils.TruncateAt.END);
        role.setPadding(0, a.dp(4), a.dp(6), 0);
        words.addView(role);
        card.setFocusable(true);
        card.setContentDescription("与 " + name + " 开始会话");
        card.setOnClickListener(v -> a.startAgentChat(agent.optString("id"), name));
        if (builtin) {
            card.addView(label("›", 21, a.muted, false));
        } else {
            Button edit = a.button("编辑", View.generateViewId(), () -> edit(agent));
            edit.setContentDescription("编辑 " + name);
            card.addView(edit, a.lp(-2, 44));
        }
    }

    private EditText field(LinearLayout body, String title, String value, int max, boolean multiline) {
        TextView caption = label(title, 14, a.secondary, true);
        a.pad(caption, 2, 12);
        body.addView(caption);
        EditText edit = new EditText(a);
        edit.setId(View.generateViewId());
        caption.setLabelFor(edit.getId());
        edit.setTextColor(a.ink);
        edit.setHintTextColor(a.muted);
        edit.setTextSize(16);
        edit.setContentDescription(title);
        edit.setBackground(a.bg(a.surface, 12, true));
        edit.setInputType(InputType.TYPE_CLASS_TEXT | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        edit.setSingleLine(!multiline);
        edit.setGravity(multiline ? Gravity.TOP | Gravity.START : Gravity.CENTER_VERTICAL | Gravity.START);
        edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});
        edit.setText(value);
        a.pad(edit, 12, 10);
        body.addView(edit, a.lp(-1, multiline ? 132 : 48));
        return edit;
    }

    private void edit(JSONObject agent) {
        boolean creating = agent == null;
        String id = creating ? UUID.randomUUID().toString() : agent.optString("id");
        screen("agent_edit");
        LinearLayout bar = a.row();
        a.pad(bar, 10, 4);
        a.root.addView(bar, a.lp(-1, 56));
        bar.addView(a.icon(R.drawable.ic_back, View.generateViewId(), "返回角色模板", this::roles), a.lp(44, 44));
        bar.addView(label(creating ? "添加角色模板" : "编辑角色模板", 20, a.ink, true));
        a.divider(a.root);
        LinearLayout body = scrollBody();
        EditText name = field(body, "名称", creating ? "" : agent.optString("name"), 64, false);
        EditText instructions = field(body, "角色指令", creating ? "" : agent.optString("instructions"), 4000, true);
        View gap = new View(a);
        body.addView(gap, a.lp(1, 18));
        Button save = primary("保存", () -> {});
        body.addView(save, a.lp(-1, 46));
        Button archive = a.button("归档角色模板", View.generateViewId(), () -> {});
        if (!creating) {
            archive.setTextColor(a.failure);
            LinearLayout.LayoutParams box = a.lp(-1, 46);
            box.topMargin = a.dp(12);
            body.addView(archive, box);
        }
        setupFeedback(body);
        save.setOnClickListener(v -> {
            String title = name.getText().toString().trim(), role = instructions.getText().toString().trim();
            if (title.isEmpty()) { name.setError("请填写名称"); name.requestFocus(); return; }
            if (role.isEmpty()) { instructions.setError("请填写角色指令"); instructions.requestFocus(); return; }
            JSONObject data = new JSONObject();
            try {
                if (creating) data.put("id", id);
                data.put("name", title);
                data.put("instructions", role);
            } catch (JSONException e) { message("无法保存表单", true); return; }
            change(creating ? "/api/agents" : "/api/agents/" + id, data, save, archive);
        });
        archive.setOnClickListener(v -> new AlertDialog.Builder(a)
            .setTitle("归档 " + agent.optString("name") + "？")
            .setMessage("归档后将从可用角色模板移除，已有会话仍可继续。")
            .setNegativeButton("取消", null)
            .setPositiveButton("归档", (dialog, which) -> {
                JSONObject data = new JSONObject();
                try { data.put("archived", true); }
                catch (JSONException e) { message("无法提交归档", true); return; }
                change("/api/agents/" + id, data, save, archive);
            }).show());
    }

    private void change(String path, JSONObject data, Button save, Button archive) {
        final int screen = a.generation;
        final String origin = a.base, access = a.token;
        save.setEnabled(false);
        archive.setEnabled(false);
        message("正在保存…", false);
        a.network.execute(() -> {
            try {
                Api.call(origin, access, path, data);
                a.ui.post(() -> { if (current(screen)) roles(); });
            } catch (Exception error) {
                a.ui.post(() -> {
                    if (!current(screen) || unauthorized(error)) return;
                    save.setEnabled(true);
                    archive.setEnabled(true);
                    message(error instanceof Api.Failure ? error.getMessage() : "未收到工作站回执，请按原内容重试。", true);
                });
            }
        });
    }
}
