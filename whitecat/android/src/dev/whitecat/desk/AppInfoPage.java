package dev.whitecat.desk;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.format.Formatter;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;

final class AppInfoPage {
    private final ChatActivity a;
    AppInfoPage(ChatActivity activity){a=activity;}
    void show(){
        a.page="app_info";a.generation++;a.frame();
        LinearLayout bar=a.row();a.pad(bar,8,4);a.root.addView(bar,a.lp(-1,-2));
        bar.addView(a.icon(R.drawable.ic_back,R.id.back,"返回设置",a::drawSettings),a.lp(48,48));
        TextView heading=a.text("应用信息",21,a.ink);a.bold(heading);bar.addView(heading);a.divider(a.root);
        ScrollView scroll=new ScrollView(a);a.root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout body=a.col();a.pad(body,18,14);scroll.addView(body);
        LinearLayout about=card(body);LinearLayout brand=a.row();about.addView(brand);brand.addView(a.cat(),a.lp(40,40));
        TextView name=a.text("白猫",21,a.ink);a.bold(name);a.pad(name,10,0);brand.addView(name);
        try{
            PackageInfo pkg=a.getPackageManager().getPackageInfo(a.getPackageName(),0);
            long build=Build.VERSION.SDK_INT>=28?pkg.getLongVersionCode():pkg.versionCode;
            line(about,"版本",pkg.versionName+"  ("+build+")").setTag("about_version");
        }catch(PackageManager.NameNotFoundException ignored){}
        line(about,"包名",a.getPackageName()).setTag("about_package");
        line(about,"系统","Android "+Build.VERSION.RELEASE+"  ·  API "+Build.VERSION.SDK_INT);
        File preferences=new File(a.getDataDir(),"shared_prefs");
        LinearLayout files=card(body);
        TextView title=a.text("本机存储",19,a.ink);a.bold(title);files.addView(title);
        TextView desc=a.text("会话缓存、草稿和设置保存在应用私有目录。",14,a.secondary);a.pad(desc,0,6);files.addView(desc);
        path(files,"会话与设置目录","prefs",preferences);
        path(files,"应用文件目录","files",a.getFilesDir());
        path(files,"临时缓存目录","cache",a.getCacheDir());
        TextView usage=line(files,"设置与会话缓存文件占用","正在读取…");usage.setTag("about_usage");
        int generation=a.generation;
        a.network.execute(()->{
            File[] entries=preferences.listFiles((dir,filename)->filename.endsWith(".xml"));
            long size=0;if(entries!=null)for(File item:entries)size+=item.length();
            String value=entries==null?"暂时无法读取":Formatter.formatFileSize(a,size);
            a.ui.post(()->{if(a.alive()&&generation==a.generation)usage.setText(value);});
        });
    }
    private LinearLayout card(LinearLayout body){
        LinearLayout box=a.col();a.pad(box,16,14);box.setBackground(a.bg(a.surface,15,false));
        LinearLayout.LayoutParams layout=a.lp(-1,-2);layout.bottomMargin=a.dp(14);body.addView(box,layout);return box;
    }
    private TextView line(LinearLayout parent,String label,String value){
        TextView caption=a.text(label,13,a.muted);caption.setPadding(0,a.dp(12),0,a.dp(2));parent.addView(caption);
        TextView text=a.text(value,16,a.ink);text.setTextIsSelectable(true);parent.addView(text);return text;
    }
    private void path(LinearLayout parent,String title,String key,File file){
        String path=file.getAbsolutePath();
        LinearLayout row=a.row();parent.addView(row);row.addView(a.text(title,14,a.secondary),a.weight());
        Button copy=a.button("复制",View.generateViewId(),()->{
            ClipboardManager clipboard=(ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText(title,path));
            Toast.makeText(a,"路径已复制",Toast.LENGTH_SHORT).show();
        });
        copy.setContentDescription("复制"+title);copy.setTag("about_copy_"+key);copy.setTextColor(a.secondary);copy.setStateListAnimator(null);copy.setBackground(a.roundControl(0));row.addView(copy,a.lp(-2,-2));
        TextView location=a.text(path,15,a.ink);location.setTextIsSelectable(true);location.setTag("about_"+key);
        location.setPadding(0,0,0,a.dp(8));parent.addView(location);a.divider(parent);
    }
}
