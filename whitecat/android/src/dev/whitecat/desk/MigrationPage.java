package dev.whitecat.desk;

import android.content.*;
import android.graphics.*;
import android.os.SystemClock;
import android.util.Base64;
import android.view.*;
import android.widget.*;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.json.*;

final class MigrationPage {
    static final int SCAN_REQUEST=813;
    final ChatActivity a;
    String step="";
    LinearLayout body;
    TextView feedback;
    MigrationPage(ChatActivity activity){a=activity;}

    void start(String stage,String title,boolean landing){
        step=stage;
        a.page="transfer";a.generation++;a.frame();
        LinearLayout top=a.row();a.pad(top,16,6);a.root.addView(top,a.lp(-1,62));
        if(landing){top.addView(a.cat(),a.lp(32,32));TextView brand=a.text("白猫",18,a.ink);a.bold(brand);a.pad(brand,10,0);top.addView(brand);}
        else{
            top.addView(a.icon(R.drawable.ic_back,View.generateViewId(),"返回迁移",this::show),a.lp(44,44));
            TextView name=a.text(title,19,a.ink);a.bold(name);top.addView(name);
        }
        a.divider(a.root);
        ScrollView scroll=new ScrollView(a);a.root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        body=a.col();a.pad(body,20,18);scroll.addView(body);
        if(landing){TextView h=a.text(title,32,a.ink);a.bold(h);body.addView(h);gap(18);}
        feedback=a.text("",14,a.secondary);feedback.setTag("migration_status");feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        if(landing) a.homeNav("transfer");
    }
    void gap(int height){body.addView(new View(a),a.lp(1,height));}
    void label(String text){TextView t=a.text(text,15,a.secondary);body.addView(t);}
    void card(String title,String sub,String tag,Runnable action){
        LinearLayout box=a.col();a.pad(box,16,16);box.setBackground(a.bg(a.surface,16,true));
        box.setTag(tag);box.setFocusable(true);box.setOnClickListener(v->action.run());body.addView(box,a.lp(-1,-2));
        TextView t=a.text(title,19,a.ink);a.bold(t);box.addView(t);
        TextView s=a.text(sub,14,a.muted);s.setPadding(0,a.dp(5),0,0);box.addView(s);gap(12);
    }
    Button action(String title,String tag,Runnable run){
        Button b=a.button(title,View.generateViewId(),run);b.setTag(tag);b.setBackground(a.bg(a.blue,14,false));body.addView(b,a.lp(-1,48));return b;
    }
    boolean current(int generation){return a.alive() && a.generation==generation && "transfer".equals(a.page);}
    boolean back(){if(!"transfer".equals(a.page)||"home".equals(step))return false;show();return true;}
    void show(){
        start("home","资料迁移",true);
        card("旧手机 · 生成迁移码","选择要迁出的本地资料","migration_old",this::scope);
        card("新手机 · 扫码导入","扫描二维码，或从相册识别","migration_new",this::scan);
        gap(8);label("两台设备须配对同一工作站。");gap(12);body.addView(feedback);
        recoverReceipt();
    }
    void scan(){a.startActivityForResult(new Intent(a,MigrationScanner.class),SCAN_REQUEST);}
    CheckBox choice(String text,String tag){
        CheckBox opt=new CheckBox(a);opt.setText(text);opt.setTextColor(a.ink);opt.setTextSize(16);opt.setTag(tag);
        opt.setChecked(true);body.addView(opt,a.lp(-1,48));return opt;
    }
    void scope(){
        start("scope","迁出资料",false);
        TextView h=a.text("选择迁移范围",24,a.ink);a.bold(h);body.addView(h);gap(16);
        CheckBox chats=choice("会话记录与索引","migration_chats"),drafts=choice("本机草稿","migration_drafts"),prefs=choice("偏好设置与聊天角色引用","migration_settings");
        final String currentId=MigrationData.id(a.chat) && !MigrationData.hidden(a.prefs,a.chat)?a.chat:null;
        RadioGroup scope=new RadioGroup(a);body.addView(scope);
        RadioButton current=new RadioButton(a);current.setId(View.generateViewId());current.setText("仅当前会话");current.setTag("migration_current");current.setTextColor(a.ink);
        current.setEnabled(currentId!=null);scope.addView(current);
        RadioButton all=new RadioButton(a);all.setId(View.generateViewId());all.setText("全部已缓存会话");all.setTag("migration_all");all.setTextColor(a.ink);scope.addView(all);
        scope.check(currentId==null?all.getId():current.getId());
        gap(18);Button go=action("生成迁移码","migration_generate",()->{});gap(10);body.addView(feedback);
        go.setOnClickListener(v->{
            boolean c=chats.isChecked(),d=drafts.isChecked(),p=prefs.isChecked();
            if(!(c||d||p)){feedback.setText("请至少选择一项资料");return;}
            go.setEnabled(false);feedback.setText("正在加密资料…");
            String only=current.isChecked()?currentId:null;
            int scene=a.generation;SharedPreferences profile=a.prefs;String base=a.base,access=a.token;
            a.network.execute(()->{try{
                JSONObject data=MigrationData.snapshot(profile,c,d,p,only);
                byte[] secret=MigrationData.random(32);
                String box=MigrationData.encrypt(data,secret);
                JSONObject result=Api.call(base,access,"/api/migration/create",new JSONObject().put("box",box));
                String value=MigrationData.PREFIX+result.getString("ticket")+"."+Base64.encodeToString(secret,MigrationData.URL64);
                Arrays.fill(secret,(byte)0);Bitmap bitmap=qr(value);
                long end=SystemClock.elapsedRealtime()+1000L*result.getInt("ttl");
                a.ui.post(()->{if(current(scene))codePage(bitmap,end);});
            }catch(Exception e){a.ui.post(()->{if(current(scene)){go.setEnabled(true);feedback.setText(error(e,"迁移码生成失败，请检查工作站连接"));}});}});
        });
    }
    static String error(Exception e,String fallback){
        if(e instanceof Api.Failure)return e.getMessage();
        if(e instanceof IOException && e.getMessage()!=null && (e.getMessage().contains("8 MB")||e.getMessage().contains("4 MB")))return e.getMessage();
        return fallback;
    }
    static Bitmap qr(String value) throws Exception {
        int size=600;BitMatrix m=new QRCodeWriter().encode(value,BarcodeFormat.QR_CODE,size,size);
        int[] px=new int[size*size];
        for(int y=0;y<size;y++)for(int x=0;x<size;x++)px[y*size+x]=m.get(x,y)?Color.BLACK:Color.WHITE;
        return Bitmap.createBitmap(px,size,size,Bitmap.Config.ARGB_8888);
    }
    void codePage(Bitmap bitmap,long end){
        start("code","迁移二维码",false);body.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView h=a.text("使用新手机扫描",24,a.ink);a.bold(h);body.addView(h);gap(18);
        ImageView image=new ImageView(a);image.setImageBitmap(bitmap);image.setTag("migration_qr");image.setContentDescription("迁移二维码");
        int side=Math.min(280,(int)(a.getResources().getDisplayMetrics().widthPixels/a.getResources().getDisplayMetrics().density)-40);
        body.addView(image,a.lp(side,side));gap(16);body.addView(feedback);gap(20);action("返回迁移","migration_return",this::show);
        int scene=a.generation;
        Runnable timer=new Runnable(){public void run(){
            if(!current(scene))return;
            long s=Math.max(0,(end-SystemClock.elapsedRealtime()+999)/1000);
            if(s==0){image.setImageDrawable(null);feedback.setText("迁移码已过期，请返回重新生成");return;}
            feedback.setText(String.format(java.util.Locale.ROOT,"迁移码剩余 %d:%02d 有效",s/60,s%60));a.ui.postDelayed(this,1000);
        }};timer.run();
    }
    static boolean validCode(String v){
        return v!=null&&v.length()==MigrationData.PREFIX.length()+87&&v.startsWith(MigrationData.PREFIX)
            &&v.substring(MigrationData.PREFIX.length()).matches("[A-Za-z0-9_-]{43}\\.[A-Za-z0-9_-]{43}");
    }
    boolean result(int request,int result,Intent data){
        if(request!=SCAN_REQUEST)return false;
        if(result==android.app.Activity.RESULT_OK&&data!=null){
            String code=data.getStringExtra("migration");
            if(validCode(code)&&!a.token.isEmpty())preview(code);
            else{show();feedback.setText("迁移码无效或工作站尚未配对");}
        }
        return true;
    }
    void preview(String code){
        start("claim","导入资料",false);body.addView(feedback);feedback.setText("正在从已配对的工作站读取…");
        String[] parts=code.substring(MigrationData.PREFIX.length()).split("\\.",2);
        String ticket=parts[0],base=a.base,access=a.token;int scene=a.generation;
        a.network.execute(()->{try{
            JSONObject got=Api.call(base,access,"/api/migration/claim",new JSONObject().put("ticket",ticket));
            if(got.optBoolean("done")){a.ui.post(()->{if(current(scene))finishPage("这份资料已导入本机");});return;}
            byte[] key=Base64.decode(parts[1],MigrationData.URL64);
            JSONObject payload=MigrationData.decrypt(got.getString("box"),key);Arrays.fill(key,(byte)0);
            a.ui.post(()->{if(current(scene))confirm(ticket,payload);});
        }catch(Exception e){a.ui.post(()->{if(current(scene)){feedback.setText(error(e,"读取失败，请检查连接和迁移码后重试"));gap(14);action("重新扫码","migration_retry",this::scan);}});}});
    }
    void confirm(String ticket,JSONObject data){
        start("confirm","导入资料",false);
        TextView h=a.text("待导入",25,a.ink);a.bold(h);body.addView(h);gap(14);
        label(MigrationData.summary(data));gap(10);label("保留本机已有的草稿、设置和较新的会话。工作站继续使用本机配对。");
        gap(24);Button yes=action("确认导入","migration_confirm",()->{});gap(12);body.addView(feedback);
        yes.setOnClickListener(v->{
            yes.setEnabled(false);
            try{
                String prior=a.prefs.getString("migration.receipt","");
                String saved=prior.isEmpty()?"":Vault.open(prior);
                if(!ticket.equals(saved))MigrationData.merge(a.prefs,data,Vault.seal(ticket));
                a.reloadLocalIndex();a.interval=a.prefs.getInt("interval",2000);a.enterSends=a.prefs.getBoolean("enterSends",false);
                finishPage("资料已导入");recoverReceipt();
            }catch(Exception e){yes.setEnabled(true);feedback.setText("本机保存失败，请重试");}
        });
    }
    void finishPage(String message){
        start("done","导入资料",false);TextView h=a.text(message,24,a.green);h.setTag("migration_done");a.bold(h);
        body.addView(h);gap(12);body.addView(feedback);gap(22);action("返回迁移","migration_return",this::show);
    }
    void recoverReceipt(){
        SharedPreferences profile=a.prefs;String sealed=profile.getString("migration.receipt","");
        if(sealed.isEmpty())return;
        String base=a.base,access=a.token;int scene=a.generation;
        a.network.execute(()->{
            boolean clear=false;
            try{
                String ticket=Vault.open(sealed);
                clear=Api.call(base,access,"/api/migration/ack",new JSONObject().put("ticket",ticket)).optBoolean("done");
            }catch(Api.Failure e){clear=e.status==410;}catch(Exception ignored){}
            boolean done=clear;
            a.ui.post(()->{
                if(done&&sealed.equals(profile.getString("migration.receipt","")))profile.edit().remove("migration.receipt").apply();
                if(current(scene)&&!done)feedback.setText("资料已保存，连接恢复后会重试确认");
            });
        });
    }
}
