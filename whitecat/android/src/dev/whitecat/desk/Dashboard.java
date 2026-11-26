package dev.whitecat.desk;

import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import org.json.*;

final class Dashboard {
    private final ChatActivity a;
    private LinearLayout rows;
    private TextView serverName,serverStatus,stationName, status, counts, recentLabel;
    private String stamp="";
    Dashboard(ChatActivity activity){a=activity;}

    void show(){
        a.page="home";a.generation++;a.paired=true;a.frame();stamp="";
        LinearLayout top=a.row();a.pad(top,16,7);a.root.addView(top,a.lp(-1,60));top.addView(a.cat(),a.lp(32,32));
        TextView brand=a.text("白猫",18,a.ink);a.bold(brand);a.pad(brand,10,0);top.addView(brand,a.weight());
        top.addView(a.icon(R.drawable.ic_plus,R.id.new_chat,"新建会话",()->a.openChat("")),a.lp(44,44));
        ScrollView scroll=new ScrollView(a);a.root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout body=a.col();a.pad(body,16,8);scroll.addView(body);
        TextView heading=a.text("工作台",32,a.ink);a.bold(heading);a.pad(heading,4,12);body.addView(heading);
        LinearLayout server=a.col();a.pad(server,16,12);server.setBackground(a.bg(a.surface,16,false));
        server.setTag("home_server");body.addView(server);
        serverName=a.text(a.activeServerName(),18,a.ink);a.bold(serverName);server.addView(serverName);
        serverStatus=a.text("",14,a.secondary);server.addView(serverStatus);
        TextView executorTitle=a.text("执行 Agent",16,a.ink);a.bold(executorTitle);a.pad(executorTitle,4,12);body.addView(executorTitle);
        LinearLayout station=a.col();station.setBackground(a.bg(a.surface,16,false));a.pad(station,16,14);body.addView(station);
        station.setOnClickListener(v->a.navigate("agents"));station.setContentDescription("选择执行 Agent");station.setFocusable(true);
        stationName=a.text("",17,a.ink);a.bold(stationName);station.addView(stationName);
        status=a.text("",14,a.secondary);a.pad(status,0,4);station.addView(status);counts=a.text("",14,a.secondary);station.addView(counts);
        LinearLayout actions=a.row();
        LinearLayout.LayoutParams createBox=a.lp(-1,-2);createBox.topMargin=a.dp(12);body.addView(actions,createBox);
        Button choose=a.button("选择 Agent",View.generateViewId(),()->a.navigate("agents"));choose.setTag("home_agents");
        actions.addView(choose,new LinearLayout.LayoutParams(0,-2,1));
        Button chats=a.button("查看会话",View.generateViewId(),()->a.navigate("list"));chats.setTag("home_chats");chats.setBackground(a.bg(a.blue,12,false));
        LinearLayout.LayoutParams chatBox=new LinearLayout.LayoutParams(0,-2,1);chatBox.leftMargin=a.dp(8);actions.addView(chats,chatBox);
        recentLabel=a.text("最近会话",14,a.secondary);recentLabel.setPadding(a.dp(3),a.dp(22),0,a.dp(7));body.addView(recentLabel);
        rows=a.col();rows.setId(R.id.home_recent);body.addView(rows);a.homeNav("home");update();a.refresh();
    }

    void update(){
        String id=a.workers.selected();
        JSONObject worker=a.workers.find(id);
        JSONArray scoped=a.workers.chats(id);
        boolean online=worker!=null&&worker.optBoolean("online");
        serverName.setText(a.activeServerName());serverStatus.setText(a.connected?"当前服务器 · 已连接":"当前服务器 · 本机缓存");
        stationName.setText(worker==null&&scoped.length()==0?"选择执行 Agent":a.workers.name(id));
        status.setText(!a.connected?"本机缓存":worker==null?"接入待确认":online?"● 执行 Agent 在线":"○ 执行 Agent 离线");
        status.setTextColor(a.connected&&online?a.green:a.amber);
        int active=0;for(int i=0;i<scoped.length();i++){
            String state=scoped.optJSONObject(i).optString("status");
            if(!scoped.optJSONObject(i).has("remote_state")&&(state.equals("running")||state.equals("queued")))active++;
        }
        counts.setText(active+" 项进行中  ·  "+scoped.length()+" 个会话");
        String next=scoped.toString();if(next.equals(stamp))return;stamp=next;rows.removeAllViews();
        for(int i=0;i<Math.min(5,scoped.length());i++){
            JSONObject item=scoped.optJSONObject(i);String chatId=item.optString("chat");
            LinearLayout card=a.col();card.setTag(chatId);card.setBackground(a.bg(a.surface,12,false));a.pad(card,14,9);
            TextView name=a.text(item.optString("title"),16,a.ink);name.setSingleLine();name.setEllipsize(TextUtils.TruncateAt.END);
            name.setTypeface(Typeface.DEFAULT,Typeface.BOLD);card.addView(name);
            String remote=RemoteHistory.label(item.optString("remote_state"));
            TextView meta=a.text(item.optString("agent_name","Codex")+"  ·  "+(remote.isEmpty()?a.status(item.optString("status")):remote),13,remote.isEmpty()?a.secondary:a.amber);card.addView(meta);
            card.setOnClickListener(v->a.openChat(chatId));card.setFocusable(true);
            LinearLayout.LayoutParams layout=a.lp(-1,-2);layout.bottomMargin=a.dp(6);rows.addView(card,layout);
        }
        if(scoped.length()==0)rows.addView(a.text("还没有会话",15,a.muted));
    }
}
