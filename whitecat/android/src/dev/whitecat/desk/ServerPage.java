package dev.whitecat.desk;

import android.app.AlertDialog;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.*;

final class ServerPage {
    private final ChatActivity a;
    String formName="", formAddress="";
    ServerPage(ChatActivity activity){a=activity;}

    private LinearLayout screen(String key,String title,Runnable back) {
        a.page=key; a.generation++; a.frame();
        LinearLayout top=a.row(); a.pad(top,10,6); a.root.addView(top,a.lp(-1,-2));
        top.addView(a.icon(R.drawable.ic_back,R.id.back,"返回",back),a.lp(44,44));
        TextView heading=a.text(title,20,a.ink); a.bold(heading); top.addView(heading,a.weight());
        a.divider(a.root);
        ScrollView scroll=new ScrollView(a); a.root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout body=a.col(); a.pad(body,20,18); scroll.addView(body);
        return body;
    }

    void show() {
        LinearLayout body=screen("servers","服务器管理",a::backFromServers);
        for (ServerProfiles.Server server:a.servers.entries.values()) {
            boolean current=server.url.equals(a.base), paired=a.servers.paired(server.url);
            LinearLayout tile=a.row(); a.pad(tile,14,12); tile.setBackground(a.bg(a.surface,14,current));
            LinearLayout.LayoutParams bounds=a.lp(-1,-2); bounds.bottomMargin=a.dp(10); body.addView(tile,bounds);
            LinearLayout words=a.col(); words.setTag("server_open_"+server.url); words.setFocusable(true);
            words.setOnClickListener(v->a.selectServer(server.url)); tile.addView(words,a.weight());
            tile.setOnClickListener(v->a.selectServer(server.url));
            TextView name=a.text(server.name,18,a.ink); a.bold(name); words.addView(name);
            String status=(current?"当前服务器 · ":"")+(paired?(current?"已配对":"已配对 · 可以切换"):"待配对");
            TextView detail=a.text(status,14,current?a.green:a.secondary); a.pad(detail,0,4); words.addView(detail);
            words.addView(a.text(server.url,13,a.muted));
            View edit=a.icon(R.drawable.ic_edit,View.generateViewId(),"重命名 "+server.name,()->rename(server));
            edit.setTag("server_rename_"+server.url); tile.addView(edit,a.lp(44,44));
        }
        Button add=a.button("添加服务器",View.generateViewId(),()->{formName="";formAddress="";form();});
        add.setTag("servers_add"); add.setMinimumHeight(a.dp(48)); add.setBackground(a.bg(a.blue,12,false));
        body.addView(add,a.lp(-1,-2));
    }

    private EditText input(String name,String tag,String value,int max) {
        EditText field=new EditText(a); field.setTag(tag); field.setSingleLine(true); field.setTextColor(a.ink);
        field.setHintTextColor(a.muted); field.setHint(name); field.setContentDescription(name);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)}); field.setText(value);
        return field;
    }

    void form() {
        LinearLayout body=screen("server_add","添加服务器",this::show);
        TextView nameLabel=a.text("显示名称",15,a.ink); body.addView(nameLabel);
        EditText name=input("显示名称","server_name",formName,64); body.addView(name,a.lp(-1,-2));
        TextView addrLabel=a.text("移动网关地址",15,a.ink); a.pad(addrLabel,0,8); body.addView(addrLabel);
        EditText address=input("移动网关地址","server_address",formAddress,2048);
        address.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        address.setHint("https://desk.example.org:18744"); body.addView(address,a.lp(-1,-2));
        TextView help=a.text("远程服务器使用 HTTPS。本机 USB 转发可使用 http://127.0.0.1:18744。",14,a.secondary);
        a.pad(help,0,10); body.addView(help);
        Button next=a.button("填写配对信息",View.generateViewId(),()->{
            formName=name.getText().toString().trim(); formAddress=address.getText().toString().trim();
            if (formName.isEmpty()){name.setError("请输入显示名称");return;}
            final String url;
            try {url=ServerProfiles.canonical(formAddress);}
            catch(Exception e){address.setError("请输入有效的 HTTPS 网关地址或本机 USB 地址");return;}
            try {a.servers.add(url,formName);}
            catch(IllegalStateException e){address.setError(e.getMessage());return;}
            a.selectServer(url);
        });
        next.setTag("server_pair"); next.setMinimumHeight(a.dp(48)); next.setBackground(a.bg(a.blue,12,false));
        body.addView(next,a.lp(-1,-2));
    }

    private void rename(ServerProfiles.Server server) {
        LinearLayout box=a.col();a.pad(box,20,4);
        EditText field=input("显示名称","server_rename_name",server.name,64); field.selectAll();box.addView(field);
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle("修改显示名称").setView(box)
                .setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String name=field.getText().toString().trim();
            if (name.isEmpty()) {field.setError("请输入显示名称");return;}
            if (!a.servers.rename(server.url,name)) {field.setError("显示名称保存失败，请重试");return;}
            dialog.dismiss();show();
        }));
        dialog.show();
    }
}
