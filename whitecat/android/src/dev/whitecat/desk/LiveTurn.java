package dev.whitecat.desk;

import android.graphics.Typeface;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;

/** Native item identities own their views for the duration of one rendered turn. */
final class LiveTurn {
    final ChatActivity a;
    final LinearLayout box;
    final boolean finished;
    final Map<String,Part> views=new LinkedHashMap<>();
    final Set<String> expanded;
    void networkChanged(){for(Part p:views.values())if(p.item!=null)update(p.item);}
    static class Part {TextView text,state,chevron,detail,icon,duration;Chronometer elapsed;ProgressBar spinner;FrameLayout mark;LinearLayout row,group;String kind;JSONObject item;}
    LiveTurn(ChatActivity a,LinearLayout parent,boolean finished,Set<String> expanded){
        this.a=a;this.finished=finished;this.expanded=expanded;box=a.col();parent.addView(box);
    }
    static String tool(String type){
        switch(type){case "shell":return "运行命令";case "mcp":return "调用 MCP 工具";case "web":return "搜索网页";
            case "files":return "更新文件";default:return "执行工具";}
    }
    void update(JSONObject item){
        String id=item.optString("item"),kind=item.optString("kind"),status=item.optString("status"),body=item.optString("body");
        boolean words=kind.equals("answer")||kind.equals("commentary");
        Part p=views.get(id);
        if(p==null){p=new Part();p.group=a.col();p.kind=kind;box.addView(p.group);views.put(id,p);
            if(words){
                p.text=a.text("",16,a.ink);
                p.text.setTextIsSelectable(true);p.text.setLineSpacing(a.dp(2),1);a.pad(p.text,0,6);p.group.addView(p.text);
            }else{
                p.row=a.row();p.row.setMinimumHeight(a.dp(44));a.pad(p.row,0,1);
                p.group.addView(p.row);p.icon=a.text("",14,a.green);p.icon.setGravity(Gravity.CENTER);p.mark=new FrameLayout(a);p.row.addView(p.mark,a.lp(24,28));p.mark.addView(p.icon,new FrameLayout.LayoutParams(-1,-1));
                p.spinner=new ProgressBar(a,null,android.R.attr.progressBarStyleSmall);p.spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(a.amber));p.mark.addView(p.spinner,new FrameLayout.LayoutParams(a.dp(14),a.dp(14),Gravity.CENTER));
                p.text=a.text("",14,a.ink);p.text.setSingleLine();p.text.setEllipsize(android.text.TextUtils.TruncateAt.END);p.text.setTypeface(Typeface.DEFAULT,Typeface.BOLD);p.text.setMaxWidth(a.dp(146));p.row.addView(p.text);
                p.state=a.text("",13,a.secondary);a.pad(p.state,9,0);p.row.addView(p.state);p.row.addView(new View(a),new LinearLayout.LayoutParams(0,a.dp(1),1));
                p.elapsed=new Chronometer(a);p.elapsed.setTextSize(12);p.elapsed.setTextColor(a.muted);a.pad(p.elapsed,5,0);p.row.addView(p.elapsed);
                p.elapsed.setTypeface(Typeface.MONOSPACE);p.duration=a.text("",12,a.muted);p.duration.setTypeface(Typeface.MONOSPACE);a.pad(p.duration,5,0);p.row.addView(p.duration);p.chevron=a.text("",14,a.muted);p.row.addView(p.chevron);
                p.detail=a.text("",13,a.secondary);a.pad(p.detail,12,10);p.detail.setBackground(a.bg(a.surface,10,false));LinearLayout.LayoutParams detailBox=a.lp(-1,-2);detailBox.leftMargin=a.dp(25);p.group.addView(p.detail,detailBox);
                final Part view=p;p.row.setOnClickListener(v->{if(!expanded.remove(id))expanded.add(id);disclose(id,view);});
                LinearLayout.LayoutParams gap=(LinearLayout.LayoutParams)p.group.getLayoutParams();gap.bottomMargin=a.dp(4);p.group.setLayoutParams(gap);
            }
        }
        p.item=item;
        if(words){
            p.group.setVisibility((kind.equals("answer")&&finished)||body.isEmpty()?View.GONE:View.VISIBLE);
            if(!body.contentEquals(p.text.getText()))p.text.setText(ChatText.inline(body,a.tile));
        }else{
            boolean active=status.equals("running"),ok=status.equals("done");
            String state=active?"执行中":ok?"已完成":status.equals("interrupted")?"已中断":"失败";
            p.icon.setText(active?"◉":ok?"✓":"×");p.icon.setTextColor(active?a.amber:ok?a.green:a.failure);
            String label=item.optString("label","");p.text.setText(label.isEmpty()?tool(kind):label);p.state.setText(state);p.state.setTextColor(active?a.amber:ok?a.secondary:a.failure);
            p.spinner.setVisibility(active&&a.connected?View.VISIBLE:View.GONE);p.icon.setVisibility(active&&a.connected?View.GONE:View.VISIBLE);
            long start=item.optLong("started_ms"),end=item.optLong("ended_ms");
            boolean ticking=active&&start>0&&a.connected;
            p.elapsed.stop();p.elapsed.setVisibility(ticking?View.VISIBLE:View.GONE);
            if(ticking){p.elapsed.setBase(android.os.SystemClock.elapsedRealtime()-Math.max(0,System.currentTimeMillis()-start));p.elapsed.start();}
            boolean timed=!active&&end>=start&&start>0;p.duration.setVisibility(timed?View.VISIBLE:View.GONE);
            if(timed)p.duration.setText(android.text.format.DateUtils.formatElapsedTime((end-start)/1000));
            int progress=item.optInt("progress");
            p.detail.setText(tool(kind)+(progress>0?" · 已收到 "+progress+" 次进度更新":" · 原生 Codex CLI"));
            p.row.setContentDescription(p.text.getText()+"，"+state+"，查看进度");p.row.setFocusable(true);disclose(id,p);
        }
    }
    void disclose(String id,Part p){boolean open=expanded.contains(id);p.detail.setVisibility(open?View.VISIBLE:View.GONE);p.chevron.setText(open?"⌄":"›");}
}
