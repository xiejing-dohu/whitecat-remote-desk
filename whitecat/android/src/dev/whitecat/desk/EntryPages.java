package dev.whitecat.desk;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.net.URI;
import java.util.Locale;

final class EntryPages {
    private static final String USB_URL = "http://127.0.0.1:18744";
    private final ChatActivity a;
    private String remoteDraft = "";

    EntryPages(ChatActivity activity) {
        a = activity;
    }

    void welcome() {
        a.page = "welcome";
        a.generation++;
        a.frame();

        ScrollView scroll = new ScrollView(a);
        scroll.setFillViewport(true);
        a.root.addView(scroll, a.lp(-1, -1));
        LinearLayout body = a.col();
        a.pad(body, 22, 12);
        scroll.addView(body);

        space(body, 1f);

        LinearLayout hero = a.col();
        hero.setGravity(Gravity.CENTER);
        a.pad(hero, 0, 20);
        body.addView(hero, a.lp(-1, -2));
        ImageView cat = a.cat();
        cat.setBackground(a.bg(a.surface, 25, true));
        cat.setTag("entry_cat");
        hero.addView(cat, a.lp(100, 100));
        TextView name = a.text("白猫", 42, a.ink);
        name.setGravity(Gravity.CENTER);
        a.bold(name);
        hero.addView(name, margin(18));
        TextView subtitle = a.text("你的 Codex 工作台", 17, a.secondary);
        subtitle.setGravity(Gravity.CENTER);
        hero.addView(subtitle, margin(3));

        space(body, 1f);
        Button enter = button(a.token.isEmpty() ? "首次连接" : "进入工作台", "entry_go", a::enterWorkspace);
        primary(enter);
        body.addView(enter, margin(12));
        space(body, .45f);
    }

    void guide(boolean remote) {
        a.page = "guide";
        a.generation++;
        a.frame();

        LinearLayout top = a.row();
        a.pad(top, 8, 4);
        a.root.addView(top, a.lp(-1, -2));
        View back = a.icon(R.drawable.ic_back, R.id.back, "返回", a::backFromGuide);
        back.setTag("guide_back");
        top.addView(back, a.lp(48, 48));
        TextView title = a.text("配置教程", 20, a.ink);
        a.bold(title);
        top.addView(title, a.weight());

        ScrollView scroll = new ScrollView(a);
        scroll.setTag("guide_scroll");
        a.root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout body = a.col();
        a.pad(body, 18, 14);
        scroll.addView(body);
        EditText address = remote ? addressField() : null;
        LinearLayout routes = a.row();
        routes.setBaselineAligned(false);
        body.addView(routes, a.lp(-1, -2));
        addRoute(routes, "本机 · USB", "guide_local", !remote, () -> {
            remoteDraft = address.getText().toString();
            a.openGuide(false);
        });
        addRoute(routes, "指定远程主机", "guide_remote", remote, () -> a.openGuide(true));

        String python = remote ? "python3 -B " : "python -B ";
        LinearLayout station = step(body, remote ? "指定执行主机" : "启动本机工作站",
                remote ? "在指定 Linux 主机上运行 API 和 Codex 工作站。" : "在电脑上运行 API 和已登录 Codex 的工作站。", "station");
        note(station, "准备 Python 3.10 或以上版本。在 whitecat 目录确认 Codex 已登录，再分别打开两个终端，依次启动 API 和 worker，并保持运行。已有进程可继续使用。");
        command(station, "login", "codex login status");
        command(station, "server", python + "server.py");
        command(station, "worker", python + "worker.py");
        note(station, "默认 Agent 使用现有工作站。在服务器本地 Web 工作台的「配置接入 Agent」中填写接入标识和名称，然后用生成的 station.json 单独启动该 Agent 的 worker。每个 Agent 可建立多条独立会话。");
        if (remote) {
            note(station, "worker 主动连接 127.0.0.1:18742 的 API；手机通过移动网关访问。API 与执行机分开部署时，执行机需通过已授权的 SSH 隧道连接 API，并使用该 API 的 worker.key。");
        }

        LinearLayout gateway = step(body, remote ? "提供 HTTPS 入口" : "连接手机",
                remote ? "将手机可访问的域名指向移动网关，证书须受 Android 信任。" : "通过 USB 调试连接手机，将手机的 18744 转发到电脑。", "gateway");
        if (remote) {
            note(gateway, "在 API 主机启动网关。直接提供 TLS 时，将命令中的网卡 IP、证书链和私钥路径换为实际值。证书应覆盖填写的域名，手机需要能访问该主机的 18744 端口。");
            command(gateway, "gateway", python + "mobile_gateway.py serve --bind YOUR_PRIVATE_IP --port 18744 --cert /path/to/fullchain.pem --key /path/to/privkey.pem");
            note(gateway, "已有 HTTPS 反向代理时，可改用下方命令让网关监听本机。把 HTTPS 入口（通常是 443）转发到 127.0.0.1:18744，并支持 SSE 长连接。18742 保持仅本机访问。");
            command(gateway, "proxy_gateway", python + "mobile_gateway.py serve");
        } else {
            note(gateway, "另开终端启动移动网关。安装 Android SDK Platform-Tools，手机开启 USB 调试并在屏幕上授权这台电脑，再运行 ADB 转发。");
            command(gateway, "gateway", python + "mobile_gateway.py serve");
            command(gateway, "adb", "adb reverse tcp:18744 tcp:18744");
            note(gateway, "若连接了多台设备，可用 adb devices 查看序列号，在 reverse 前加 -s 和目标序列号。设备重启或再次插线后，可重新运行转发。");
        }

        LinearLayout pair = step(body, remote ? "填写远程地址" : "在手机配对",
                "在网关主机生成一次性配对码，再到手机填写。", "pair");
        note(pair, "在运行移动网关的同一账号与数据目录下执行。12 位配对码在 10 分钟内有效，可兑换一次。");
        command(pair, "pair", python + "mobile_gateway.py pair");
        note(pair, "打开配对页，输入配对码并点「连接工作站」。首页显示「工作站在线」后，新建会话发送一句话，即可验证执行机能回复。");

        if (remote) {
            TextView fieldTitle = a.text("移动网关 HTTPS 地址", 15, a.ink);
            a.bold(fieldTitle);
            fieldTitle.setLabelFor(address.getId());
            body.addView(fieldTitle, margin(20));
            body.addView(address, margin(6));
            note(body, "填写手机可访问的域名及实际端口。使用 HTTPS 代理的默认 443 时可省略端口。");
        } else {
            TextView local = a.text("USB 移动网关  ·  " + USB_URL, 14, a.secondary);
            local.setTextIsSelectable(true);
            body.addView(local, margin(18));
        }
        TextView error = a.text("", 14, a.failure);
        error.setTag("guide_error");
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        error.setVisibility(View.GONE);
        body.addView(error, margin(6));
        Runnable fill = () -> {
            String url = USB_URL;
            if (remote) {
                remoteDraft = address.getText().toString();
                try {
                    url = remoteEndpoint(remoteDraft);
                } catch (IllegalArgumentException e) {
                    error.setText(e.getMessage());
                    error.setVisibility(View.VISIBLE);
                    address.requestFocus();
                    return;
                }
            }
            a.pairFromGuide(url);
        };
        Button next = button("填写配对信息", "guide_pair", fill);
        primary(next);
        body.addView(next, margin(16));
        if (remote) {
            address.addTextChangedListener(new android.text.TextWatcher(){
                public void beforeTextChanged(CharSequence value,int start,int count,int after){}
                public void onTextChanged(CharSequence value,int start,int before,int count){
                    remoteDraft=value.toString();
                    error.setText("");
                    error.setVisibility(View.GONE);
                }
                public void afterTextChanged(android.text.Editable value){}
            });
            address.setOnEditorActionListener((view, action, event) -> {
                if (action != EditorInfo.IME_ACTION_DONE) return false;
                fill.run();
                return true;
            });
        }
    }

    static String remoteEndpoint(String input) {
        try {
            URI entered = new URI(input.trim());
            String endpoint = Api.endpoint(input);
            URI url = new URI(endpoint);
            if (!"https".equalsIgnoreCase(url.getScheme())) {
                throw new IllegalArgumentException("远程移动网关需要 HTTPS 地址。");
            }
            if (entered.getRawAuthority().endsWith(":") || url.getPort() == 0 || url.getPort() > 65535) {
                throw new IllegalArgumentException("请输入 1 至 65535 的有效网关端口。");
            }
            String host = url.getHost().toLowerCase(Locale.ROOT);
            if (host.equals("localhost") || host.equals("localhost.") || host.endsWith(".localhost")
                    || host.matches("127(\\.[0-9]+){1,3}") || host.equals("[::1]") || host.equals("[0:0:0:0:0:0:0:1]")) {
                throw new IllegalArgumentException("此地址指向手机本机，请填写远程移动网关的域名或 IP。");
            }
            return endpoint;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("请输入完整的 HTTPS 网关地址，仅包含域名或 IP 和可选端口。");
        }
    }

    private EditText addressField() {
        EditText field = new EditText(a);
        field.setId(View.generateViewId());
        field.setTag("guide_address");
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.setTextColor(a.ink);
        field.setHintTextColor(a.muted);
        field.setBackground(a.bg(a.surface, 12, true));
        field.setMinHeight(a.dp(48));
        a.pad(field, 12, 8);
        field.setHint("https://desk.example.com:18744");
        field.setText(remoteDraft);
        return field;
    }

    private void addRoute(LinearLayout routes, String label, String tag, boolean active, Runnable select) {
        Button option = button(label, tag, () -> { if (!active) select.run(); });
        option.setSelected(active);
        option.setTextColor(a.ink);
        option.setBackground(a.bg(active ? a.blue : a.surface, 13, !active));
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(0, -2, 1);
        if (routes.getChildCount() > 0) layout.leftMargin = a.dp(8);
        routes.addView(option, layout);
    }

    private LinearLayout step(LinearLayout body, String title, String summary, String key) {
        LinearLayout card = a.col();
        a.pad(card, 15, 11);
        card.setBackground(a.bg(a.surface, 16, true));
        body.addView(card, margin(13));
        LinearLayout header = a.col();
        header.setMinimumHeight(a.dp(48));
        header.setFocusable(true);
        header.setTag("guide_step_" + key);
        card.addView(header, a.lp(-1, -2));
        LinearLayout line = a.row();
        header.addView(line, a.lp(-1, -2));
        TextView heading = a.text(title, 17, a.ink);
        a.bold(heading);
        line.addView(heading, a.weight());
        TextView toggle = a.text("＋", 20, a.secondary);
        line.addView(toggle, a.lp(-2, -2));
        note(header, summary);
        LinearLayout details = a.col();
        details.setVisibility(View.GONE);
        card.addView(details);
        header.setContentDescription(title + "，" + summary + " 展开步骤和命令");
        header.setOnClickListener(v -> {
            boolean open = details.getVisibility() != View.VISIBLE;
            details.setVisibility(open ? View.VISIBLE : View.GONE);
            toggle.setText(open ? "−" : "＋");
            header.setContentDescription(title + "，" + summary + (open ? " 收起步骤和命令" : " 展开步骤和命令"));
        });
        return details;
    }

    private void note(LinearLayout box, String message) {
        TextView text = a.text(message, 14, a.secondary);
        box.addView(text, margin(6));
    }

    private void command(LinearLayout box, String key, String value) {
        LinearLayout row = a.row();
        row.setTag("guide_copy_" + key);
        row.setBackground(a.bg(a.tile, 10, false));
        a.pad(row, 10, 8);
        row.setMinimumHeight(a.dp(44));
        row.setFocusable(true);
        row.setContentDescription("复制命令，" + value);
        row.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("白猫命令", value));
            Toast.makeText(a, "已复制命令", Toast.LENGTH_SHORT).show();
        });
        TextView command = a.text(value, 13, a.ink);
        command.setTypeface(Typeface.MONOSPACE);
        row.addView(command, a.weight());
        TextView copy = a.text("复制", 13, a.secondary);
        copy.setPadding(a.dp(8), 0, 0, 0);
        row.addView(copy, a.lp(-2, -2));
        box.addView(row, margin(6));
    }

    private Button button(String label, String tag, Runnable click) {
        Button button = a.button(label, View.generateViewId(), click);
        button.setTag(tag);
        button.setMinHeight(a.dp(48));
        button.setMinimumHeight(a.dp(48));
        a.pad(button, 12, 8);
        return button;
    }

    private void primary(Button button) {
        button.setBackground(a.bg(a.blue, 15, false));
        button.setTextColor(a.ink);
        a.bold(button);
    }

    private LinearLayout.LayoutParams margin(int top) {
        LinearLayout.LayoutParams layout = a.lp(-1, -2);
        layout.topMargin = a.dp(top);
        return layout;
    }

    private void space(LinearLayout parent, float weight) {
        parent.addView(new View(a), new LinearLayout.LayoutParams(1, 0, weight));
    }
}
