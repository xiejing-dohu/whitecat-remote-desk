package dev.whitecat.desk;

import android.app.*;
import android.os.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;
import android.text.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public class ChatActivity extends Activity {
    int ink, paper, muted, tile, green, surface, rail, edge, secondary, blue, amber, failure;
    final Handler ui=new Handler(Looper.getMainLooper());
    final ExecutorService network=Executors.newFixedThreadPool(2);
    SharedPreferences prefs;
    String base,token="",chat="",pending="",filename="",sample="",last="",fileChat="";
    JSONArray chats=new JSONArray();
    boolean paired,sending,polling,running,resumed,connected,stationOnline;
    String page="chat",filter="",listStamp="",stationName="Codex 工作站";
    LinearLayout listRows;
    TextView listStatus;
    int interval=2000;
    boolean enterSends,nativeBound;
    long retryAfter;
    ServerProfiles servers;
    final ServerPage serverPage=new ServerPage(this);
    String serversFrom="welcome";
    boolean changingServer;
    SharedPreferences profile(String url){return servers.profile(url);}
    String activeServerName(){return servers.name(base);}
    void openServers(){
        if(page.equals("chat")&&input!=null)saveDraft();
        serversFrom=page;serverPage.show();
    }
    void backFromServers(){
        if(serversFrom.equals("settings"))drawSettings();
        else if(serversFrom.equals("home")&&!token.isEmpty())dashboard.show();
        else entryPages.welcome();
    }
    void selectServer(String url){
        if(!servers.paired(url)){
            pairFrom="servers";suggestedAddress=url;pairCodeInMemory="";pairPage();return;
        }
        activateServer(url);
    }
    void activateServer(String url){
        if(changingServer)return;
        if(!servers.activate(url)){Toast.makeText(this,"服务器切换未保存，请重试",Toast.LENGTH_SHORT).show();return;}
        // Keep old requests, receipts and their SharedPreferences on this Activity until destruction.
        // The replacement Activity opens only the new profile, including its own offline index and outbox.
        changingServer=true;generation++;paired=false;stopFeed();ui.removeCallbacks(poll);recreate();
    }
    void restoreProfile(){
        chat=prefs.getString("chat","");pending=prefs.getString("pending","");
        reloadLocalIndex();if(prefs.getBoolean("hidden."+chat,false))chat="";
        if(!remoteState().isEmpty())preservePendingDraft();
        interval=prefs.getInt("interval",2000);enterSends=prefs.getBoolean("enterSends",false);
    }
    int generation;
    LinearLayout root,transcript;
    ScrollView scroll;
    EditText input;
    TextView hint,presence,title,fileLabel;
    ImageButton send,attach;
    Button retry;
    Set<String> expanded=new HashSet<>();
    final Runnable poll=new Runnable(){public void run(){refresh(); if(resumed&&(page.equals("home")||page.equals("list")||page.equals("agents")||(page.equals("chat")&&(chat.isEmpty()||waitForIndex()))))ui.postDelayed(this,interval);}};

    final AppInfoPage appInfo=new AppInfoPage(this);
    final EntryPages entryPages=new EntryPages(this);
    final AppearancePage appearancePage=new AppearancePage(this);
    String guideFrom="welcome",appearanceFrom="welcome",pairFrom="welcome",suggestedAddress="",pairCodeInMemory="";
    boolean guideRemote;
    @Override protected void attachBaseContext(Context context){super.attachBaseContext(Appearance.context(context));}
    @Override public void onCreate(Bundle state){
        setTheme(Appearance.theme(this));
        super.onCreate(state);
        Appearance.apply(this);
        servers=new ServerProfiles(this);base=servers.active();prefs=profile(base);stationName=activeServerName();restoreProfile();
        if(servers.paired(base))try {token=Vault.open(prefs.getString("access",""));} catch(Exception e){token="";}
        if(state!=null){
            if(state.getBoolean("serverChanged")||!base.equals(state.getString("savedProfile",base))){
                state.remove("android:viewHierarchyState");
                if(token.isEmpty()){suggestedAddress=base;pairFrom="servers";pairPage();}
                else if(pending.isEmpty())dashboard.show();
                else {chatPage();refresh();}
                return;
            }
            serversFrom=state.getString("serversFrom","welcome");
            serverPage.formName=state.getString("serverFormName","");serverPage.formAddress=state.getString("serverFormAddress","");
            appearanceFrom=state.getString("appearanceFrom","welcome");guideFrom=state.getString("guideFrom","welcome");
            settingsFrom=state.getString("settingsFrom","home");pairFrom=state.getString("pairFrom","welcome");
            suggestedAddress=state.getString("suggestedAddress","");guideRemote=state.getBoolean("guideRemote");
            firstPairAddress=state.getString("firstPairAddress","");
            String screen=state.getString("screen","welcome");
            if(screen.equals("servers")){serverPage.show();return;}
            if(screen.equals("server_add")){serverPage.form();return;}
            if(screen.equals("settings")){drawSettings();return;}
            if(screen.equals("app_info")){appInfo.show();return;}
            if(screen.equals("guide")){entryPages.guide(guideRemote);return;}
            if(screen.equals("appearance")){appearancePage.show();return;}
            if(screen.equals("pair")){pairPage();return;}
            if(!screen.equals("welcome")&&!token.isEmpty()){
                paired=true;
                if(screen.equals("list")||screen.equals("agents")||screen.equals("transfer")){navigate(screen);return;}
                enterWorkspace();return;
            }
        }
        entryPages.welcome();
        if(!pending.isEmpty()&&!token.isEmpty())ui.postDelayed(()->{
            if(alive()&&page.equals("welcome"))enterWorkspace();
        },450);
    }
    @Override protected void onSaveInstanceState(Bundle state){
        super.onSaveInstanceState(state);state.putString("screen",page);
        state.putString("savedProfile",base);state.putBoolean("serverChanged",changingServer);state.putString("serversFrom",serversFrom);
        if(page.equals("server_add")){
            EditText n=root.findViewWithTag("server_name"),u=root.findViewWithTag("server_address");
            state.putString("serverFormName",n.getText().toString());state.putString("serverFormAddress",u.getText().toString());
        }
        state.putString("appearanceFrom",appearanceFrom);state.putString("guideFrom",guideFrom);
        state.putString("settingsFrom",settingsFrom);state.putString("pairFrom",pairFrom);
        state.putString("firstPairAddress",firstPairAddress);state.putString("suggestedAddress",suggestedAddress);state.putBoolean("guideRemote",guideRemote);
    }
    void enterWorkspace(){
        if(token.isEmpty()){pairFrom="welcome";pairPage();return;}
        if(!pending.isEmpty()||prefs.getString("start_screen","home").equals("chat")){chatPage();refresh();}
        else dashboard.show();
    }
    void openAppearance(){appearanceFrom=page;appearancePage.show();}
    void backFromAppearance(){drawSettings();}
    void openGuide(boolean remote){
        if(!page.equals("guide")){
            if(!page.equals("pair")||!pairFrom.equals("guide"))guideFrom=page;
            if(page.equals("pair")){
                EditText endpoint=findViewById(R.id.endpoint),code=findViewById(R.id.pair_code);
                suggestedAddress=endpoint.getText().toString();pairCodeInMemory=code.getText().toString();
            }
        }
        guideRemote=remote;entryPages.guide(remote);
    }
    void backFromGuide(){
        if(guideFrom.equals("settings"))drawSettings();
        else if(guideFrom.equals("pair"))pairPage();
        else entryPages.welcome();
    }
    void pairFromGuide(String url){
        if(!pending.isEmpty()&&!url.equals(base)){Toast.makeText(this,"请先确认原工作站的待发送任务",Toast.LENGTH_LONG).show();return;}
        if(!url.equals(suggestedAddress))pairCodeInMemory="";
        suggestedAddress=url;
        // Preserve the original pair form's Back destination when re-entering it from its help page.
        if(!guideFrom.equals("pair"))pairFrom="guide";
        pairPage();
    }
    @Override protected void onResume(){super.onResume(); resumed=true;ui.removeCallbacks(poll);ui.post(poll);}
    @Override protected void onPause(){resumed=false;stopFeed();ui.removeCallbacks(poll);super.onPause();}
    @Override protected void onDestroy(){network.shutdownNow();ui.removeCallbacksAndMessages(null);super.onDestroy();}
    final Dashboard dashboard=new Dashboard(this);
    final AgentPage agentsPage=new AgentPage(this);
    final ExecutorDirectory workers=new ExecutorDirectory(this);
    TextView executorCaption, listExecutorTitle;
    final MigrationPage migrationPage=new MigrationPage(this);
    TextView roleChip;
    String settingsFrom="home";
    String firstPairAddress="",firstPairCode="";

    String roleName(){
        if(chat.isEmpty())return prefs.getString("selected_agent_name","Codex");
        if(document!=null&&document.has("agent_name"))return document.optString("agent_name","Codex");
        for(int i=0;i<chats.length();i++){
            JSONObject c=chats.optJSONObject(i);if(chat.equals(c.optString("chat")))return c.optString("agent_name","Codex");
        }
        return "Codex";
    }
    void startAgentChat(String agentId,String label){
        if(waitingForReceipt())return;
        prefs.edit().putString("selected_agent",agentId).putString("selected_agent_name",label).apply();
        openChat("");
    }
    boolean waitingForReceipt(){
        if(pending.isEmpty()&&!sending)return false;
        Toast.makeText(this,"待发送的消息仍保留在本机，请先确认送达。",Toast.LENGTH_SHORT).show();return true;
    }
    void navigate(String target){
        if(waitingForReceipt())return;
        if(page.equals("chat")&&input!=null)saveDraft();
        switch(target){
            case "home":dashboard.show();break;
            case "list":history();break;
            case "agents":agentsPage.show();break;
            case "roles":agentsPage.roles();break;
            case "transfer":migrationPage.show();break;
            case "settings":settingsFrom=page;drawSettings();break;
        }
    }
    void homeNav(String active){
        divider(root);LinearLayout bar=row();bar.setBackgroundColor(surface);root.addView(bar,lp(-1,62));
        String[] keys={"home","list","agents","transfer","settings"},names={"首页","会话","Agent","迁移","我的"};
        int[] ids={R.id.nav_home,R.id.nav_list,R.id.nav_agents,R.id.nav_transfer,R.id.nav_account};
        int[] icons={R.drawable.ic_home,R.drawable.ic_chat,R.drawable.ic_agents,R.drawable.ic_transfer,R.drawable.ic_account};
        for(int i=0;i<keys.length;i++){
            String key=keys[i];boolean current=active.equals(key);
            LinearLayout tab=col();tab.setId(ids[i]);tab.setGravity(Gravity.CENTER);tab.setSelected(current);tab.setFocusable(true);
            tab.setContentDescription(names[i]+(current?"，当前页":""));tab.setOnClickListener(v->{if(!current)navigate(key);});
            ImageView image=new ImageView(this);image.setImageDrawable(symbol(icons[i],current?ink:muted));image.setScaleType(ImageView.ScaleType.CENTER);
            if(current)image.setBackground(bg(tile,14,false));tab.addView(image,lp(52,26));
            TextView title=text(names[i],12,current?ink:muted);title.setGravity(Gravity.CENTER);tab.addView(title);bar.addView(tab,new LinearLayout.LayoutParams(0,-1,1));
        }
    }
    JSONArray visibleIndex(JSONArray source){
        JSONArray visible=new JSONArray();if(source==null)return visible;
        for(int i=0;i<source.length();i++){
            JSONObject item=source.optJSONObject(i);
            if(item!=null&&!prefs.getBoolean("hidden."+item.optString("chat"),false))visible.put(item);
        }
        return visible;
    }
    String remoteState(){
        if(chat.isEmpty())return "";
        JSONObject row=RemoteHistory.find(chats,chat);
        return row==null?"":row.optString("remote_state");
    }
    boolean waitForIndex(){
        String state=remoteState();
        return RemoteHistory.DELETED.equals(state)||RemoteHistory.UNKNOWN.equals(state);
    }
    boolean preservePendingDraft(){
        if(pending.isEmpty())return true;
        try{
            JSONObject request=new JSONObject(pending);
            if(!chat.equals(request.optString("chat")))return true;
            if(!prefs.edit().putString("draft."+chat,pending).remove("pending").commit())return false;
            pending="";return true;
        }catch(JSONException ignored){return false;}
    }
    void remoteNotice(){
        String state=remoteState();if(state.isEmpty())return;
        showPresence(RemoteHistory.label(state)+" · 只读",false);
        hint.setText(document.optJSONArray("turns")==null?"本机未缓存此会话的消息":"可查看本机保存的消息和草稿");
    }
    JSONObject indexRow(String id)throws JSONException{
        JSONObject row=RemoteHistory.find(chats,id);
        if(row!=null)return row;
        JSONObject cache=jsonPref("cache."+id);
        JSONArray turns=cache.optJSONArray("turns");
        JSONObject tail=turns==null?null:turns.optJSONObject(turns.length()-1);
        JSONObject item=new JSONObject().put("chat",id).put("title",cache.optString("title","白猫工作台"))
            .put("agent_name",cache.optString("agent_name","Codex")).put("remote_instance",cache.optString("instance"));
        if(cache.has("executor"))item.put("executor",cache.get("executor")).put("executor_name",cache.optString("executor_name"));
        if(tail!=null)item.put("created",tail.optDouble("created",0)).put("status",tail.optString("status"))
            .put("preview",tail.optString("answer",tail.optString("prompt")));
        return item;
    }
    boolean recordRemote(String id,String state,String instance)throws JSONException{
        if(!chat.equals(id)||prefs.getBoolean("hidden."+id,false))return false;
        JSONObject row=indexRow(id);
        boolean missing=RemoteHistory.find(chats,id)==null;
        if(missing||!state.equals(row.optString("remote_state"))||!instance.equals(row.optString("remote_instance"))){
            if(!state.isEmpty())row.put("remote_state",state);else row.remove("remote_state");
            if(!instance.isEmpty())row.put("remote_instance",instance);
            if(missing)chats.put(row);
            if(!prefs.edit().putString("index",chats.toString()).commit())return false;
        }
        if(!state.isEmpty()){
            if(!preservePendingDraft())return false;
            remoteNotice();controls();
        }
        return true;
    }
    boolean acceptUnavailable(JSONObject message){
        try{
            String state=RemoteHistory.evidence(indexRow(chat),message,chat);
            if(state.isEmpty())return false;
            String instance=state.equals(RemoteHistory.UNKNOWN)?indexRow(chat).optString("remote_instance"):message.getString("instance");
            if(!recordRemote(chat,state,instance))return false;
            if(waitForIndex()){
                stopFeed();ui.removeCallbacks(poll);if(resumed)ui.postDelayed(poll,interval);
            }
            return true;
        }catch(JSONException ignored){return false;}
    }
    void reloadLocalIndex(){
        try{chats=visibleIndex(new JSONArray(prefs.getString("index","[]")));}catch(JSONException e){chats=new JSONArray();}
        listStamp="";
    }
    void confirmLocalDelete(JSONObject item){
        String id=item.optString("chat");
        new AlertDialog.Builder(this).setTitle("仅从本机删除？")
            .setMessage("将清除「"+item.optString("title")+"」在这台设备上的会话缓存和草稿。此操作仅影响本机。")
            .setNegativeButton("取消",null).setPositiveButton("仅从本机删除",(d,w)->deleteLocal(id)).show();
    }
    void deleteLocal(String id){
        if(id.isEmpty()||waitingForReceipt())return;
        JSONArray remaining=new JSONArray();
        for(int i=0;i<chats.length();i++){JSONObject item=chats.optJSONObject(i);if(item!=null&&!id.equals(item.optString("chat")))remaining.put(item);}
        SharedPreferences.Editor change=prefs.edit().putBoolean("hidden."+id,true).remove("cache."+id).remove("draft."+id).putString("index",remaining.toString());
        boolean selected=id.equals(chat);if(selected)change.remove("chat");
        if(!change.commit()){Toast.makeText(this,"本机存储写入失败，请重试。",Toast.LENGTH_SHORT).show();return;}
        if(selected){stopFeed();chat="";document=new JSONObject();liveTurns.clear();expanded.clear();toolDetails.clear();nativeBound=false;running=false;}
        chats=remaining;listStamp="";drawList();Toast.makeText(this,"已从本机删除",Toast.LENGTH_SHORT).show();
    }
    boolean alive(){return !changingServer&&!isFinishing()&&!isDestroyed();}
    int dp(float v){return Math.round(getResources().getDisplayMetrics().density*v);}
    LinearLayout col(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    void pad(View v,int x,int y){v.setPadding(dp(x),dp(y),dp(x),dp(y));}
    LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w<0?w:dp(w),h<0?h:dp(h));}
    LinearLayout.LayoutParams weight(){return new LinearLayout.LayoutParams(0,-2,1);}
    GradientDrawable bg(int color,int radius,boolean border){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));if(border)d.setStroke(dp(1),edge);return d;
    }
    TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);return t;}
    void bold(TextView t){t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);}
    Button button(String label,int id,Runnable action){
        Button b=new Button(this);b.setId(id);b.setText(label);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(ink);
        b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(dp(44));pad(b,10,4);b.setBackground(bg(tile,14,false));
        b.setOnClickListener(v->action.run());return b;
    }
    ImageView cat(){ImageView i=new ImageView(this);i.setImageResource(R.mipmap.chat_cat);i.setBackground(bg(surface,8,true));pad(i,1,1);i.setClipToOutline(true);i.setContentDescription("白猫");return i;}
    void frame(){
        stopFeed();jump=null;ui.removeCallbacks(poll);if(resumed)ui.post(poll);
        if(paired&&(page.equals("chat")||page.equals("home")||page.equals("list")||page.equals("agents")||page.equals("transfer")))prefs.edit().putString("start_screen",page.equals("chat")?"chat":"home").apply();
        root=col();root.setBackgroundColor(paper);root.setFocusableInTouchMode(true);setContentView(root);
        if(Build.VERSION.SDK_INT>=35)root.setOnApplyWindowInsetsListener((v,in)->{
            android.graphics.Insets bars=in.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());
            v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return in;});
    }
    void pairPage(){
        if(token.isEmpty()&&prefs.contains("access"))prefs.edit().putBoolean("pair_required",true).apply();
        paired=false;page="pair";generation++;frame();ScrollView content=new ScrollView(this);root.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout box=col();pad(box,24,20);content.addView(box);
        box.addView(icon(R.drawable.ic_back,R.id.back,"返回",this::backFromPair),lp(44,44));
        box.addView(cat(),lp(62,62));TextView name=text("连接白猫工作站",25,ink);bold(name);pad(name,0,12);box.addView(name);
        box.addView(text("输入工作站地址和一次性配对码。使用 USB 转发时，可保留默认地址。",15,muted));
        EditText endpoint=new EditText(this);endpoint.setId(R.id.endpoint);endpoint.setSingleLine();endpoint.setHint("服务地址");endpoint.setText(suggestedAddress.isEmpty()?base:suggestedAddress);box.addView(endpoint);
        if(pairFrom.equals("servers")&&!suggestedAddress.isEmpty())endpoint.setEnabled(false);
        EditText code=new EditText(this);code.setId(R.id.pair_code);code.setSingleLine();code.setHint("12 位配对码");
        code.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);code.setSaveEnabled(false);
        code.setText(pairCodeInMemory);box.addView(code);
        TextView feedback=text("",14,muted);box.addView(feedback);
        final int pairingPage=generation;
        Button connect=button("连接工作站",R.id.pair_button,()->{});box.addView(connect);
        if(token.isEmpty() && pairFrom.equals("welcome")){
            Button options=button("设置",View.generateViewId(),()->{
                firstPairAddress=endpoint.getText().toString();
                firstPairCode=code.getText().toString();
                settingsFrom="pair";drawSettings();
            });
            options.setTag("pair_settings");box.addView(options,lp(-1,-2));
        }
        connect.setOnClickListener(v->{
            final String url;
            try{url=ServerProfiles.canonical(endpoint.getText().toString());}catch(Exception e){feedback.setText("地址无效，请使用 HTTPS 或本机 USB 地址");return;}
            if(!pairFrom.equals("servers")&&!pending.isEmpty()&&!url.equals(base)){feedback.setText("待确认的任务须连接原工作站");return;}
            String secret=code.getText().toString().trim();
            if(!secret.matches("[A-Fa-f0-9]{12}")){feedback.setText("请输入工作站显示的 12 位配对码");return;}
            connect.setEnabled(false);feedback.setText("正在连接…");
            network.execute(()->{try{
                String access=Api.call(url,"","/pair",new JSONObject().put("code",secret)).getString("token");
                String sealed=Vault.seal(access);
                ui.post(()->{if(!alive()||pairingPage!=generation)return;
                    try{servers.add(url,"");}catch(IllegalStateException e){connect.setEnabled(true);feedback.setText(e.getMessage());return;}
                    if(!profile(url).edit().putString("base",url).putString("access",sealed).remove("pair_required").commit()){
                        connect.setEnabled(true);feedback.setText("配对信息保存失败，请重新配对");return;
                    }
                    suggestedAddress="";pairCodeInMemory="";activateServer(url);});
            }catch(Exception e){ui.post(()->{if(alive()&&generation==pairingPage){connect.setEnabled(true);feedback.setText(e instanceof Api.Failure?e.getMessage():"连接失败，请检查地址和转发");}});}});
        });
    }
    void chatPage(){
        paired=true;page="chat";generation++;last="";frame();
        nativeBound=!jsonPref("cache."+chat).isNull("native_thread");
        LinearLayout top=row();pad(top,10,5);top.setMinimumHeight(dp(62));root.addView(top,lp(-1,-2));
        top.addView(icon(R.drawable.ic_back,R.id.list_chats,"会话列表",this::history),lp(44,44));
        LinearLayout heading=col();pad(heading,6,0);top.addView(heading,weight());
        title=text(chat.isEmpty()?"新会话":"白猫工作台",18,ink);title.setId(R.id.heading);bold(title);
        title.setSingleLine();title.setEllipsize(TextUtils.TruncateAt.END);heading.addView(title);
        executorCaption=text("",13,secondary);executorCaption.setTag("chat_executor");executorCaption.setMaxLines(2);executorCaption.setEllipsize(TextUtils.TruncateAt.END);heading.addView(executorCaption);
        presence=text(connected?(stationOnline?"● 工作站在线":"○ 工作站离线"):"正在同步",11,muted);presence.setId(R.id.status);heading.addView(presence);
        heading.setOnClickListener(v->settings());
        top.addView(icon(R.drawable.ic_edit,R.id.new_chat,"新建会话",()->openChat("")),lp(44,44));divider(root);
        scroll=new ScrollView(this);scroll.setFillViewport(false);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        transcript=col();pad(transcript,20,18);transcript.setId(R.id.transcript);scroll.addView(transcript);
        jump=button("↓  查看最新消息",View.generateViewId(),()->{scroll.smoothScrollTo(0,transcript.getHeight());jump.setVisibility(View.GONE);});jump.setVisibility(View.GONE);root.addView(jump,lp(-1,36));
        scroll.setOnScrollChangeListener((v,x,y,oldX,oldY)->{if(jump!=null&&nearBottom())jump.setVisibility(View.GONE);});
        LinearLayout bottom=col();pad(bottom,12,8);root.addView(bottom);
        hint=text("",13,secondary);hint.setId(R.id.hint);bottom.addView(hint);
        hint.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int b,int c){} public void afterTextChanged(Editable e){} public void onTextChanged(CharSequence s,int a,int b,int c){hint.setVisibility(s.length()>0?View.VISIBLE:View.GONE);}});hint.setVisibility(View.GONE);
        retry=button("按原请求重试",R.id.retry,this::deliver);bottom.addView(retry,lp(-2,-2));
        LinearLayout composer=col();composer.setBackground(bg(rail,20,true));pad(composer,9,7);bottom.addView(composer);
        input=new EditText(this);input.setId(R.id.message);input.setHint("发消息给 Codex…");input.setHintTextColor(muted);input.setTextSize(16);input.setTextColor(ink);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);input.setMaxLines(4);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(10000)});
        input.setBackgroundColor(0);pad(input,6,8);input.setMinLines(1);composer.addView(input);
        input.setImeOptions(enterSends?android.view.inputmethod.EditorInfo.IME_ACTION_SEND:android.view.inputmethod.EditorInfo.IME_ACTION_NONE);
        input.setOnEditorActionListener((v,action,e)->{
            if(enterSends&&(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEND||(e!=null&&e.getKeyCode()==KeyEvent.KEYCODE_ENTER&&e.getAction()==KeyEvent.ACTION_DOWN&&!e.isShiftPressed()))){
                if(send.isEnabled())submit();return true;
            }return false;});
        fileLabel=text("",13,muted);fileLabel.setId(R.id.draft_file);fileLabel.setOnClickListener(v->{filename="";sample="";saveDraft();fileView();});composer.addView(fileLabel);
        LinearLayout bar=row();composer.addView(bar);attach=icon(R.drawable.ic_plus,R.id.attachment,"添加文本附件",this::pickFile);attach.setBackground(roundControl(tile));bar.addView(attach,lp(44,44));
        TextView model=text("Codex",14,secondary);roleChip=model;pad(model,8,0);model.setGravity(Gravity.CENTER_VERTICAL);model.setMinimumHeight(dp(44));model.setCompoundDrawablePadding(dp(4));model.setCompoundDrawables(null,null,symbol(R.drawable.ic_down,muted),null);model.setOnClickListener(v->{if(chat.isEmpty())navigate("roles");else settings();});model.setContentDescription(chat.isEmpty()?"选择聊天角色模板":"工作站设置");model.setSingleLine();model.setEllipsize(TextUtils.TruncateAt.END);bar.addView(model,weight());
        send=icon(R.drawable.ic_up,R.id.send,"发送消息",this::submit);android.graphics.drawable.StateListDrawable sendBg=new android.graphics.drawable.StateListDrawable();
        sendBg.addState(new int[]{android.R.attr.state_enabled},roundControl(blue));sendBg.addState(new int[]{},roundControl(tile));send.setBackground(sendBg);bar.addView(send,lp(44,44));
        JSONObject draft=jsonPref(workers.draftKey());
        if(!pending.isEmpty())try{draft=new JSONObject(pending);}catch(JSONException ignored){}
        input.setText(draft.optString("prompt"));filename=draft.optString("filename");sample=draft.optString("sample");fileView();
        input.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void afterTextChanged(Editable e){}public void onTextChanged(CharSequence s,int start,int before,int count){saveDraft();controls();}});
        if(!pending.isEmpty())hint.setText("上一条发送尚未确认，重试将使用原编号");
        document=jsonPref("cache."+chat);if(document.has("title"))title.setText(document.optString("title"));updateChatLabels();render(document.optJSONArray("turns"));controls();remoteNotice();
    }
    void updateChatLabels(){
        String owner=workers.currentChatOwner();
        String name=workers.name(owner);
        if(!chat.isEmpty()&&document.has("executor")&&owner.equals(ExecutorDirectory.owner(document))
                &&!document.optString("executor_name").isEmpty())name=document.optString("executor_name");
        executorCaption.setText("执行 Agent · "+name);
        roleChip.setText("角色 · "+roleName());
    }
    JSONObject jsonPref(String key){try{return new JSONObject(prefs.getString(key,"{}"));}catch(JSONException e){return new JSONObject();}}
    void saveDraft(){if(!pending.isEmpty()||!remoteState().isEmpty())return;try{String key=workers.draftKey();
        String draft=new JSONObject().put("prompt",input.getText().toString()).put("filename",filename).put("sample",sample).toString();
        if(!draft.equals(prefs.getString(key,""))&&!prefs.edit().putString(key,draft).commit())hint.setText("草稿暂未保存");}catch(JSONException ignored){}}
    void controls(){boolean waiting=!pending.isEmpty(),writable=remoteState().isEmpty();
        input.setEnabled(writable&&!waiting);attach.setEnabled(writable&&!waiting);fileLabel.setEnabled(writable&&!waiting);
        send.setEnabled(writable&&!waiting&&!sending&&(!running||nativeBound)&&!input.getText().toString().trim().isEmpty());send.setColorFilter(send.isEnabled()?ink:muted);
        retry.setVisibility(writable&&waiting?View.VISIBLE:View.GONE);retry.setEnabled(writable&&!sending);}
    void fileView(){fileLabel.setVisibility(filename.isEmpty()?View.GONE:View.VISIBLE);fileLabel.setText(filename+"  ×");}
    void pickFile(){fileChat=chat;Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("text/*");startActivityForResult(i,10);}
    @Override protected void onActivityResult(int r,int result,Intent data){super.onActivityResult(r,result,data);if(migrationPage.result(r,result,data))return;if(r!=10||result!=RESULT_OK||data==null||data.getData()==null||!fileChat.equals(chat)||!pending.isEmpty()||!remoteState().isEmpty())return;
        android.net.Uri uri=data.getData();String label="附件.txt";
        try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())label=c.getString(0);}
        catch(RuntimeException ignored){}
        try(InputStream in=getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1){if(out.size()+n>32768)throw new IOException();out.write(buf,0,n);}
            String decoded=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(out.toByteArray())).toString();
            filename=label.length()>150?label.substring(0,150):label;sample=decoded;saveDraft();fileView();
        }catch(Exception e){hint.setText("请选择 32 KB 以内的 UTF-8 文本文件");}
    }
    ImageButton icon(int drawable,int id,String label,Runnable action){
        ImageButton b=new ImageButton(this);b.setId(id);b.setImageResource(drawable);b.setScaleType(ImageView.ScaleType.CENTER);
        b.setColorFilter(ink);b.setContentDescription(label);b.setTooltipText(label);b.setPadding(0,0,0,0);
        b.setStateListAnimator(null);b.setBackground(roundControl(0));b.setOnClickListener(v->action.run());return b;
    }
    android.graphics.drawable.Drawable roundControl(int fill){
        android.graphics.drawable.InsetDrawable plate=new android.graphics.drawable.InsetDrawable(bg(fill,22,false),dp(3));
        return new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x36ffffff),plate,null);
    }
    android.graphics.drawable.Drawable symbol(int resource,int color){
        android.graphics.drawable.Drawable icon=getDrawable(resource).mutate();icon.setTint(color);
        icon.setBounds(0,0,icon.getIntrinsicWidth(),icon.getIntrinsicHeight());return icon;
    }
    void divider(LinearLayout box){View line=new View(this);line.setBackgroundColor(edge);box.addView(line,new LinearLayout.LayoutParams(-1,1));}
    String clock(double timestamp){return android.text.format.DateFormat.format("HH:mm",(long)(timestamp*1000)).toString();}
    void history(){
        if(waitingForReceipt())return;
        if(page.equals("chat")&&input!=null)saveDraft();page="list";generation++;frame();filter="";listStamp="";
        LinearLayout head=row();pad(head,16,7);root.addView(head,lp(-1,62));head.addView(icon(R.drawable.ic_back,R.id.back,"返回执行 Agent",()->navigate("agents")),lp(44,44));
        TextView brand=text(activeServerName(),18,ink);bold(brand);brand.setSingleLine();brand.setEllipsize(TextUtils.TruncateAt.END);pad(brand,6,0);head.addView(brand,weight());
        ImageButton add=icon(R.drawable.ic_plus,R.id.new_chat,"新建会话",()->openChat(""));add.setBackground(roundControl(blue));head.addView(add,lp(44,44));
        LinearLayout body=col();pad(body,16,0);root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        listExecutorTitle=text("",24,ink);listExecutorTitle.setTag("list_executor");bold(listExecutorTitle);pad(listExecutorTitle,4,10);body.addView(listExecutorTitle);
        EditText search=new EditText(this);search.setId(R.id.search);search.setSingleLine();search.setTextSize(15);search.setHint("搜索会话");
        search.setTextColor(ink);search.setHintTextColor(muted);search.setCompoundDrawables(symbol(R.drawable.ic_search,muted),null,null,null);search.setCompoundDrawablePadding(dp(10));
        search.setBackground(bg(surface,13,true));pad(search,14,4);body.addView(search,lp(-1,46));
        listStatus=text("",12,amber);pad(listStatus,4,6);body.addView(listStatus);
        ScrollView sc=new ScrollView(this);body.addView(sc);listRows=col();listRows.setId(R.id.list_rows);sc.addView(listRows);
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int b,int c){} public void afterTextChanged(Editable e){}
            public void onTextChanged(CharSequence s,int a,int b,int c){filter=s.toString().trim().toLowerCase(Locale.ROOT);drawList();}});
        homeNav("list");drawList();refresh();
    }
    void drawList(){
        String selected=workers.selected();JSONArray visible=workers.chats(selected);
        listExecutorTitle.setText(workers.name(selected)+" · 会话");
        listStatus.setVisibility(connected?View.GONE:View.VISIBLE);listStatus.setText("本机缓存 · "+visible.length()+" 个会话");
        String stamp=selected+"|"+filter+"|"+chat+"|"+visible.toString();
        if(stamp.equals(listStamp))return;listStamp=stamp;listRows.removeAllViews();String group="";
        for(int i=0;i<visible.length();i++){
            JSONObject item=visible.optJSONObject(i);String id=item.optString("chat"),name=item.optString("title"),preview=item.optString("preview");
            if(!(name+" "+preview).toLowerCase(Locale.ROOT).contains(filter))continue;
            long at=(long)(item.optDouble("created")*1000);
            String section=android.text.format.DateUtils.isToday(at)?"今天":android.text.format.DateFormat.format("M月d日",at).toString();
            if(!group.equals(section)){group=section;TextView label=text(section,12,muted);bold(label);label.setPadding(dp(4),dp(16),0,dp(6));listRows.addView(label);}
            boolean current=chat.equals(id);LinearLayout card=row();pad(card,10,9);card.setMinimumHeight(dp(66));card.setBackground(bg(current?surface:paper,12,false));
            ImageView mark=new ImageView(this);mark.setImageDrawable(symbol(R.drawable.ic_chat,current?ink:muted));mark.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            LinearLayout.LayoutParams markBox=lp(19,22);markBox.rightMargin=dp(12);markBox.topMargin=dp(2);markBox.gravity=Gravity.TOP;card.addView(mark,markBox);
            LinearLayout words=col();card.addView(words,weight());LinearLayout line=row();words.addView(line);TextView nameView=text(name,16,ink);nameView.setSingleLine();nameView.setEllipsize(TextUtils.TruncateAt.END);bold(nameView);line.addView(nameView,weight());
            TextView time=text(clock(item.optDouble("created")),11,muted);pad(time,5,0);line.addView(time);
            String remote=RemoteHistory.label(item.optString("remote_state"));
            if(!remote.isEmpty())words.addView(text(remote,13,amber));
            TextView excerpt=text("",13,secondary);excerpt.setText(ChatText.inline(preview.isEmpty()?status(item.optString("status")):preview,tile));excerpt.setPadding(0,dp(3),0,0);excerpt.setMaxLines(1);excerpt.setEllipsize(TextUtils.TruncateAt.END);words.addView(excerpt);
            ImageButton more=icon(R.drawable.ic_more,R.id.local_delete,"会话操作："+name,()->confirmLocalDelete(item));card.addView(more,lp(44,44));
            card.setOnLongClickListener(v->{confirmLocalDelete(item);return true;});card.setTag(id);card.setFocusable(true);card.setOnClickListener(v->openChat(id));listRows.addView(card,lp(-1,-2));
        }
        if(listRows.getChildCount()==0){TextView empty=text(filter.isEmpty()?"还没有会话，点右上角开始。":"没有匹配的会话",15,muted);pad(empty,4,24);listRows.addView(empty);}
    }
    void settings(){
        if(waitingForReceipt())return;
        settingsFrom=page;if(page.equals("chat"))saveDraft();drawSettings();
    }
    private void settingsRow(LinearLayout group,String label,String tag,int id,Runnable action){
        Button row=button(label,id,action);
        row.setTag(tag);row.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);pad(row,2,4);
        row.setTextSize(16);row.setMinimumHeight(dp(48));row.setStateListAnimator(null);
        android.util.TypedValue background=new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground,background,true);
        row.setBackgroundResource(background.resourceId);
        row.setCompoundDrawablesRelative(null,null,symbol(R.drawable.ic_forward,muted),null);
        group.addView(row,lp(-1,-2));
    }
    void drawSettings(){
        paired=!token.isEmpty();page="settings";generation++;frame();
        LinearLayout bar=row();pad(bar,10,6);root.addView(bar,lp(-1,58));bar.addView(icon(R.drawable.ic_back,R.id.back,"返回",this::backFromSettings),lp(44,44));
        TextView heading=text("设置",20,ink);bold(heading);bar.addView(heading);divider(root);
        ScrollView settingsScroll=new ScrollView(this);root.addView(settingsScroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout box=col();pad(box,16,12);settingsScroll.addView(box);
        TextView connection=text("连接",13,muted);pad(connection,4,8);box.addView(connection);
        LinearLayout hostPanel=col();hostPanel.setBackground(bg(surface,14,false));pad(hostPanel,14,12);box.addView(hostPanel);
        TextView station=text(token.isEmpty()?"尚未配对":activeServerName(),17,ink);bold(station);hostPanel.addView(station);TextView endpoint=text(base,14,secondary);pad(endpoint,0,6);hostPanel.addView(endpoint);
        divider(hostPanel);
        settingsRow(hostPanel,"服务器管理","settings_servers",View.generateViewId(),this::openServers);
        divider(hostPanel);
        settingsRow(hostPanel,"配置地址与配对",null,R.id.configure,()->{pairFrom="settings";suggestedAddress=base;pairCodeInMemory="";pairPage();});
        String nativeId=settingsFrom.equals("chat")?jsonPref("cache."+chat).optString("native_thread",""):"";
        if(nativeId.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")){
            TextView title=text("当前会话",15,ink);bold(title);pad(title,0,10);box.addView(title);
            TextView cmd=text("codex resume "+nativeId,13,muted);cmd.setTextIsSelectable(true);box.addView(cmd);
            box.addView(button("复制 CLI 续聊命令",View.generateViewId(),()->{
                android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("Codex","codex resume "+nativeId));
                Toast.makeText(this,"已复制续聊命令",Toast.LENGTH_SHORT).show();}),lp(-2,-2));
        }
        TextView personal=text("显示与帮助",13,muted);pad(personal,4,12);box.addView(personal);
        LinearLayout display=col();pad(display,14,2);display.setBackground(bg(surface,14,false));box.addView(display);
        settingsRow(display,"字体与背景","settings_appearance",View.generateViewId(),this::openAppearance);
        divider(display);
        settingsRow(display,"配置教程","settings_guide",View.generateViewId(),()->openGuide(base.startsWith("https://")));
        divider(display);
        settingsRow(display,"应用信息与本机存储","settings_about",View.generateViewId(),appInfo::show);
        TextView behavior=text("输入与同步",13,muted);pad(behavior,4,12);box.addView(behavior);
        LinearLayout preferences=col();preferences.setBackground(bg(surface,14,false));pad(preferences,14,5);box.addView(preferences);
        Switch enter=new Switch(this);enter.setId(R.id.enter_send);enter.setText("回车发送");enter.setChecked(enterSends);preferences.addView(enter,lp(-1,48));divider(preferences);
        enter.setOnCheckedChangeListener((v,on)->{enterSends=on;prefs.edit().putBoolean("enterSends",on).apply();});
        TextView intervalLabel=text("会话列表刷新间隔",14,muted);pad(intervalLabel,0,6);preferences.addView(intervalLabel);
        RadioGroup options=new RadioGroup(this);options.setOrientation(LinearLayout.HORIZONTAL);preferences.addView(options);
        for(int seconds:new int[]{2,5,15}){RadioButton option=new RadioButton(this);option.setText(seconds+" 秒");options.addView(option);option.setChecked(interval==seconds*1000);
            option.setOnClickListener(v->{interval=seconds*1000;prefs.edit().putInt("interval",interval).apply();});}
        if(paired && !settingsFrom.equals("chat"))homeNav("settings");
    }
    void backFromSettings(){
        if(settingsFrom.equals("pair")){
            pairFrom="welcome";suggestedAddress=firstPairAddress;pairCodeInMemory=firstPairCode;
            firstPairAddress="";firstPairCode="";pairPage();return;
        }
        if(token.isEmpty() || settingsFrom.equals("welcome")){entryPages.welcome();return;}
        if(settingsFrom.equals("chat")){chatPage();refresh();return;}
        navigate(settingsFrom);
    }
    void backFromPair(){
        pairCodeInMemory="";suggestedAddress="";
        if(pairFrom.equals("servers"))serverPage.show();
        else if(pairFrom.equals("guide"))entryPages.guide(guideRemote);
        else if(pairFrom.equals("settings"))drawSettings();
        else entryPages.welcome();
    }
    @Override public void onBackPressed(){
        if(page.equals("servers")){backFromServers();return;}
        if(page.equals("server_add")){serverPage.show();return;}
        if(page.equals("app_info")){drawSettings();return;}
        if(page.equals("appearance")){backFromAppearance();return;}
        if(page.equals("guide")){backFromGuide();return;}
        if(page.equals("pair")){backFromPair();return;}
        if(page.equals("transfer")&&migrationPage.back())return;
        if(page.equals("list")||page.equals("roles")){navigate("agents");return;}
        if(page.equals("agents")||page.equals("transfer")){navigate("home");return;}
        if(page.equals("agent_edit")){navigate("roles");return;}
        if(page.equals("chat")){navigate("list");return;}
        if(page.equals("settings")){backFromSettings();return;}
        super.onBackPressed();
    }
    void openChat(String id){
        if(waitingForReceipt()||prefs.getBoolean("hidden."+id,false))return;
        if(page.equals("chat"))saveDraft();
        if(id.isEmpty()&&workers.find(workers.selected())==null){
            Toast.makeText(this,"请先选择已接入的执行 Agent",Toast.LENGTH_SHORT).show();agentsPage.show();return;
        }
        JSONObject row=id.isEmpty()?null:RemoteHistory.find(chats,id);
        if(!id.isEmpty())workers.select(ExecutorDirectory.owner(row!=null?row:jsonPref("cache."+id)));
        chat=id;prefs.edit().putString("chat",chat).commit();
        running=false;expanded.clear();chatPage();refresh();
    }
    void submit(){
        if(!remoteState().isEmpty()||!pending.isEmpty()||sending||(running&&!nativeBound))return;
        try{
            String text=input.getText().toString().trim();if(text.isEmpty())return;
            JSONObject request=new JSONObject().put("chat",chat.isEmpty()?JSONObject.NULL:chat).put("command",UUID.randomUUID().toString())
                .put("prompt",text).put("filename",filename).put("sample",sample);
            if(chat.isEmpty())request.put("agent",prefs.getString("selected_agent","codex")).put("executor",workers.selected());
            else {
                String source=RemoteHistory.id(document,"instance");
                if(source.isEmpty())source=indexRow(chat).optString("remote_instance");
                if(!source.isEmpty())request.put("expected_instance",source);
            }
            String body=request.toString();
            if(!prefs.edit().putString("pending",body).commit()){hint.setText("无法保存待发送任务");return;}
            pending=body;deliver();
        }catch(JSONException e){hint.setText("无法生成请求");}
    }
    void deliver(){
        if(sending||pending.isEmpty()||!remoteState().isEmpty())return;sending=true;controls();hint.setText("正在提交…");
        String body=pending,origin=base,access=token;
        network.execute(()->{try{
            JSONObject receipt=Api.call(origin,access,"/api/jobs",new JSONObject(body));String next=receipt.getString("chat");
            ui.post(()->{if(!alive())return;sending=false;if(!body.equals(pending))return;
                SharedPreferences.Editor accepted=prefs.edit().remove("pending").remove(workers.draftKey()).putString("chat",next);
                if(chat.isEmpty()){
                    try{
                        JSONObject sent=new JSONObject(body), snapshot=jsonPref("cache."+next);
                        String owner=receipt.has("executor")?ExecutorDirectory.owner(receipt):ExecutorDirectory.owner(sent);
                        snapshot.put("executor",owner).put("executor_name",receipt.optString("executor_name",workers.name(owner)));
                        snapshot.put("agent",receipt.optString("agent",sent.optString("agent","codex")));
                        snapshot.put("agent_name",receipt.optString("agent_name",roleName()));
                        accepted.putString("cache."+next,snapshot.toString());
                    }catch(JSONException invalid){hint.setText("回执暂未保存，请重试");controls();return;}
                }
                if(!accepted.commit()){hint.setText("回执未保存，请按原请求重试");controls();return;}
                pending="";chat=next;running=true;chatPage();running=true;controls();refresh();});
        }catch(Exception e){ui.post(()->{if(!alive())return;sending=false;if(!body.equals(pending))return;
            if(e instanceof Api.Failure){
                Api.Failure failure=(Api.Failure)e;
                if((failure.status==404||failure.status==409||failure.status==410)&&acceptUnavailable(failure.body))return;
            }
            if(e instanceof Api.Failure&&((Api.Failure)e).status==401){token="";pairPage();return;}
            if(e instanceof Api.Failure&&(((Api.Failure)e).status==400||((Api.Failure)e).status==409||((Api.Failure)e).status==413)){
                pending="";prefs.edit().remove("pending").commit();hint.setText(e.getMessage());saveDraft();
            }else {retryAfter=SystemClock.elapsedRealtime()+5000;hint.setText("未收到回执，恢复连接后自动重试。");}controls();});}});
    }
    ChatFeed feed;
    JSONObject document=new JSONObject();
    final Map<String,LiveTurn> liveTurns=new HashMap<>();
    final Set<String> toolDetails=new HashSet<>();
    Button jump;
    void stopFeed(){if(feed!=null){feed.cancel();feed=null;}}
    boolean nearBottom(){return scroll==null||transcript.getHeight()-scroll.getHeight()-scroll.getScrollY()<dp(75);}
    void follow(boolean stick){if(stick)scroll.post(()->scroll.scrollTo(0,transcript.getHeight()));else if(jump!=null)jump.setVisibility(View.VISIBLE);}
    void showPresence(String label,boolean live){
        SpannableString words=new SpannableString(label);
        if(label.startsWith("●")||label.startsWith("○"))words.setSpan(new android.text.style.ForegroundColorSpan(live?green:amber),0,1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        presence.setTextColor(live?secondary:amber);presence.setText(words);
    }
    void startFeed(){startFeed(false);}
    void startFeed(boolean snapshot){
        if(!resumed||feed!=null||!page.equals("chat")||chat.isEmpty()||waitForIndex())return;
        final int screen=generation;final String id=chat;
        long cursor=snapshot?-1:document.optLong("stream_cursor",-1);
        feed=new ChatFeed(base,id,token,cursor,ui,new ChatFeed.Listener(){
            public boolean frame(String event,JSONObject frame)throws Exception{
                if(!alive()||generation!=screen||!id.equals(chat))return false;
                if(event.equals("unavailable"))return acceptUnavailable(frame);
                if(frame.has("chat")&&!id.equals(RemoteHistory.id(frame,"chat")))return false;
                String instance=RemoteHistory.id(frame,"instance");
                if(!event.equals("snapshot")&&!instance.isEmpty()&&!instance.equals(RemoteHistory.id(document,"instance"))){
                    // Stream cursors are local to a database, including the first sync after upgrading.
                    stopFeed();startFeed(true);return false;
                }
                boolean reconnecting=!connected;connected=true;stationOnline=frame.optBoolean("executor_online",frame.optBoolean("online",true));
                if(reconnecting)for(LiveTurn row:liveTurns.values())row.networkChanged();
                showPresence(stationOnline?"● 工作站在线 · 实时同步":"○ 工作站离线 · 历史已同步",stationOnline);
                if(!event.equals("presence")&&
                   (event.equals("snapshot")||frame.getLong("stream_cursor")>document.optLong("stream_cursor",-1))){
                    boolean bottom=nearBottom();boolean structural;
                    if(event.equals("snapshot")){document=frame;structural=true;}
                    else structural=merge(frame);
                    if(!prefs.edit().putString("cache."+id,document.toString()).commit()){
                        document=jsonPref("cache."+id);presence.setText("历史暂未保存 · 正在重试");return false;
                    }
                    nativeBound=!document.isNull("native_thread");
                    if(structural)render(document.optJSONArray("turns"));
                    else {
                        JSONArray parts=frame.optJSONArray("parts");
                        for(int j=0;parts!=null&&j<parts.length();j++){
                            JSONObject part=parts.getJSONObject(j);LiveTurn row=liveTurns.get(part.getString("job"));if(row!=null)row.update(part);
                        }
                    }
                    follow(bottom);
                }
                if(!instance.isEmpty()){
                    boolean wasReadOnly=!remoteState().isEmpty();
                    if(!recordRemote(id,RemoteHistory.nativeUnavailable(frame)?RemoteHistory.NATIVE:"",instance))return false;
                    if(wasReadOnly&&remoteState().isEmpty())hint.setText("");
                }
                title.setText(document.optString("title","白猫工作台"));updateChatLabels();controls();
                if(!pending.isEmpty()&&!sending&&SystemClock.elapsedRealtime()>=retryAfter)deliver();
                return true;
            }
            public void offline(boolean unauthorized){
                if(!alive()||generation!=screen)return;
                connected=false;for(LiveTurn row:liveTurns.values())row.networkChanged();presence.setText("○ 已断线 · 本机缓存");presence.setTextColor(amber);remoteNotice();
                if(unauthorized&&!sending){token="";pairPage();}
            }
        });feed.start();
    }
    boolean merge(JSONObject frame)throws JSONException{
        JSONArray turns=document.getJSONArray("turns");
        LinkedHashMap<String,JSONObject> byId=new LinkedHashMap<>();
        for(int i=0;i<turns.length();i++){JSONObject t=turns.getJSONObject(i);byId.put(t.getString("id"),t);}
        JSONArray changed=frame.getJSONArray("turns");
        for(int i=0;i<changed.length();i++){JSONObject t=changed.getJSONObject(i);byId.put(t.getString("id"),t);}
        JSONArray updates=frame.getJSONArray("parts");
        for(int i=0;i<updates.length();i++){
            JSONObject part=updates.getJSONObject(i),t=byId.get(part.getString("job"));if(t==null)continue;
            JSONArray items=t.optJSONArray("parts");if(items==null){items=new JSONArray();t.put("parts",items);}
            int at=0;while(at<items.length()&&!items.getJSONObject(at).getString("item").equals(part.getString("item")))at++;
            items.put(at,part);
        }
        if(changed.length()>0){
            ArrayList<JSONObject> sorted=new ArrayList<>(byId.values());
            Collections.sort(sorted,(x,y)->Double.compare(x.optDouble("created"),y.optDouble("created")));
            document.put("turns",new JSONArray(sorted));
        }
        for(String key:new String[]{"native_thread","revision","title","stream_cursor"})document.put(key,frame.get(key));
        for(String key:new String[]{"agent","agent_name","executor","executor_name"})if(frame.has(key))document.put(key,frame.get(key));
        return changed.length()>0;
    }
    void refresh(){
        if(!paired||!alive()||!(page.equals("list")||page.equals("chat")||page.equals("home")||page.equals("agents")))return;
        if(page.equals("chat")&&!chat.isEmpty()&&!waitForIndex()){startFeed();return;}
        if(polling)return;polling=true;int rev=generation;String id=page.equals("chat")?chat:"",origin=base,access=token;
        network.execute(()->{try{
            JSONObject state=Api.call(origin,access,"/api/state",null);

            ui.post(()->{polling=false;if(!alive()||rev!=generation)return;
                String before=remoteState();
                try{chats=visibleIndex(RemoteHistory.reconcile(chats,state));workers.update(state);}catch(JSONException invalid){return;}
                if(!remoteState().isEmpty())preservePendingDraft();
                String index=chats.toString();if(!prefs.getString("index","").equals(index))prefs.edit().putString("index",index).apply();
                connected=true;
                JSONObject worker=workers.find(workers.selected());
                stationOnline=worker!=null&&worker.optBoolean("online");stationName=state.optString("station_name","Codex 工作站");
                if(page.equals("list")){drawList();return;}if(page.equals("home")){dashboard.update();return;}if(page.equals("agents")){agentsPage.updateDirectory();return;}
                updateChatLabels();
                for(int i=0;i<chats.length();i++){JSONObject c=chats.optJSONObject(i);if(id.equals(c.optString("chat")))title.setText(c.optString("title"));}
                if(!chat.isEmpty()){
                    if(waitForIndex()){remoteNotice();controls();return;}
                    if(!before.isEmpty())hint.setText("");
                    startFeed();return;
                }
                showPresence(stationOnline?"● 工作站在线 · 已同步":"○ 工作站离线 · 已同步",stationOnline);
                controls();
                if(!pending.isEmpty()&&!sending&&SystemClock.elapsedRealtime()>=retryAfter)deliver();});
        }catch(Exception e){ui.post(()->{polling=false;if(!alive()||rev!=generation)return;
            if(e instanceof Api.Failure&&((Api.Failure)e).status==401&&!sending){token="";pairPage();return;}
            connected=false;if(page.equals("list")){drawList();return;}if(page.equals("home")){dashboard.update();return;}if(page.equals("agents")){agentsPage.updateDirectory();return;}presence.setText("○ 离线 · 本机缓存");presence.setTextColor(amber);remoteNotice();});}});
    }
    String status(String s){switch(s){case "queued":return "等待领取";case "running":return "正在运行";case "done":return "已完成";case "failed":return "执行失败";default:return "结果待核实";}}
    void userBubble(String message){
        TextView user=text(message,16,ink);user.setTextIsSelectable(true);user.setBackground(bg(tile,15,false));pad(user,13,10);
        LinearLayout.LayoutParams b=lp(-2,-2);b.gravity=Gravity.END;b.leftMargin=dp(30);b.bottomMargin=dp(16);transcript.addView(user,b);
    }
    void render(JSONArray turns){
        boolean bottom=transcript.getHeight()-scroll.getHeight()-scroll.getScrollY()<dp(75);
        liveTurns.clear();transcript.removeAllViews();if(turns==null||turns.length()==0){
            if(chat.isEmpty()){LinearLayout empty=col();pad(empty,0,76);empty.addView(cat(),lp(48,48));TextView h=text("想让 "+roleName()+" 做些什么？",26,ink);bold(h);pad(h,0,12);empty.addView(h);empty.addView(text("发送任务，或附上一份文本文件。",15,secondary));transcript.addView(empty);}return;}
        for(int i=0;i<turns.length();i++){
            JSONObject t=turns.optJSONObject(i);String st=t.optString("status"),id=t.optString("id");
            if(i==turns.length()-1)running=st.equals("queued")||st.equals("running");
            String file=t.optString("filename");
            JSONArray activities=t.optJSONArray("events");
            boolean fromTerminal=false;
            if(activities!=null)for(int k=0;k<activities.length();k++){
                if("来自原生 Codex CLI".equals(activities.optJSONObject(k).optString("label"))){fromTerminal=true;break;}
            }
            if(fromTerminal){TextView origin=text("工作站 · Codex CLI",12,muted);origin.setGravity(Gravity.END);transcript.addView(origin);}
            userBubble(t.optString("prompt")+(file.isEmpty()?"":"\n附件 · "+file));
            JSONArray more=t.optJSONArray("followups");
            if(more!=null)for(int j=0;j<more.length();j++)userBubble(more.optString(j));
            LinearLayout agent=row();agent.addView(cat(),lp(26,26));TextView name=text("  "+roleName(),15,ink);bold(name);agent.addView(name);transcript.addView(agent);
            JSONArray events=t.optJSONArray("events");int count=events==null?0:events.length();
            TextView stamp=text(clock(t.optDouble("created")),11,muted);stamp.setGravity(Gravity.END);agent.addView(stamp,weight());
            TextView a=text((expanded.contains(id)?"▾ ":"▸ ")+status(st)+" · "+count+" 条活动",13,secondary);pad(a,0,8);transcript.addView(a);
            LinearLayout details=col();for(int j=0;j<count;j++){JSONObject event=events.optJSONObject(j);details.addView(text(android.text.format.DateFormat.format("HH:mm:ss", (long)(event.optDouble("at")*1000))+"  "+event.optString("label"),13,muted));}
            details.setVisibility(expanded.contains(id)?View.VISIBLE:View.GONE);transcript.addView(details);
            a.setOnClickListener(v->{if(expanded.remove(id)){details.setVisibility(View.GONE);}else{expanded.add(id);details.setVisibility(View.VISIBLE);}a.setText((expanded.contains(id)?"▾ ":"▸ ")+status(st)+" · "+count+" 条活动");});
            LiveTurn live=new LiveTurn(this,transcript,!t.optString("answer").isEmpty()&&!st.equals("running"),toolDetails);liveTurns.put(id,live);
            JSONArray parts=t.optJSONArray("parts");for(int k=0;parts!=null&&k<parts.length();k++)live.update(parts.optJSONObject(k));
            if(!t.optString("answer").isEmpty()){TextView answer=text("",16,ink);answer.setTextIsSelectable(true);answer.setText(ChatText.inline(t.optString("answer"),tile));answer.setLineSpacing(dp(2),1);pad(answer,0,8);transcript.addView(answer);}
            View space=new View(this);transcript.addView(space,lp(1,16));
        }
        if(bottom)scroll.post(()->scroll.scrollTo(0,transcript.getHeight()));
    }
}
