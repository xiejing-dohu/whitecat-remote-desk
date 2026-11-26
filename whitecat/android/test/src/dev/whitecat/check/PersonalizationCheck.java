package dev.whitecat.check;

import android.app.Activity;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

/** Follow public entry points and measure the actual rendered text and root background. */
final class PersonalizationCheck {
    private final DeviceCheck d;
    PersonalizationCheck(DeviceCheck device){d=device;}
    private View tagged(String key){
        long end=SystemClock.elapsedRealtime()+9000;
        do{
            View[] match={null};
            d.runOnMainSync(()->{
                View root=d.activity.findViewById(android.R.id.content);
                if(root!=null)match[0]=root.findViewWithTag(key);
            });
            if(match[0]!=null)return match[0];
            SystemClock.sleep(90);
        }while(SystemClock.elapsedRealtime()<end);
        throw new AssertionError("Missing control "+key);
    }
    private void tap(String key){
        View v=tagged(key);d.runOnMainSync(()->{if(!v.isEnabled())throw new AssertionError("Disabled "+key);v.performClick();});
        d.waitForIdleSync();
    }
    private void back(){d.runOnMainSync(()->d.activity.onBackPressed());d.waitForIdleSync();}
    private void change(String key){
        Activity prior=d.activity;tap(key);
        long end=SystemClock.elapsedRealtime()+6500;
        while(prior==d.activity && SystemClock.elapsedRealtime()<end)SystemClock.sleep(60);
        if(prior==d.activity)throw new AssertionError("Appearance did not recreate");
        tagged("appearance_preview");
    }
    private float font(){
        View v=tagged("appearance_preview");
        if(!(v instanceof TextView))throw new AssertionError("Missing preview text");
        final float[] px={0};d.runOnMainSync(()->px[0]=((TextView)v).getTextSize());return px[0];
    }
    private int palette(){
        int[] c={0};d.runOnMainSync(()->{
            ViewGroup root=d.activity.findViewById(android.R.id.content);
            c[0]=((ColorDrawable)root.getChildAt(0).getBackground()).getColor();
        });return c[0];
    }
    private void edit(String key,String value){View v=tagged(key);d.runOnMainSync(()->((EditText)v).setText(value));}
    private void equal(String what,String expected,String actual){if(!expected.equals(actual))throw new AssertionError(what);}
    private void openMySettings(){
        tap("entry_go");
        if(d.view("list_chats")!=null)d.press("list_chats");
        d.press("nav_home");d.await("home_recent","",8);
        assertNoHomeSettings();
        d.press("nav_account");tagged("settings_about");
        if(!d.view("nav_account").isSelected())throw new AssertionError("Unexpected active tab");
    }
    private void assertNoHomeSettings(){
        d.runOnMainSync(()->{
            if(d.view("settings")!=null || d.view("configure")!=null ||
                    d.activity.findViewById(android.R.id.content).findViewWithTag("entry_settings")!=null)
                throw new AssertionError("Settings shortcut on home or launch");
        });
    }
    private void checkPair(String address){
        d.await("endpoint",address,7);final String[] value={""};d.runOnMainSync(()->value[0]=((EditText)d.view("endpoint")).getText().toString());
        equal("Incorrect pairing address",address,value[0]);
    }
    void run(String stage,Bundle result) throws Exception{
        tagged("entry_go");assertNoHomeSettings();
        d.runOnMainSync(()->{
            View host=d.activity.findViewById(android.R.id.content);
            for(String key:new String[]{"entry_appearance","entry_guide","entry_servers"})if(host.findViewWithTag(key)!=null)throw new AssertionError("Direct settings control on entry");
        });
        if(stage.equals("live_directory")){
            tap("entry_go");d.openList();d.press("nav_agents");
            View doc=tagged("executor_docs");tagged("executor_default");String[] visible={""};
            d.runOnMainSync(()->visible[0]=d.contents(doc));
            if(!visible[0].contains("文档助手") || !visible[0].contains("在线"))throw new AssertionError("14d executor offline");
            d.imageOfPage("22-workstation.png");return;
        }
        if(stage.equals("personal_choose")){
            d.imageOfPage("21-welcome.png");openMySettings();tap("settings_appearance");
            if(!tagged("appearance_bg_obsidian").isSelected() || !tagged("appearance_font_100").isSelected())change("appearance_reset");
            float before=font();int canvas=palette();
            change("appearance_font_130");float after=font();
            if(after<before*1.17f)throw new AssertionError("Font did not enlarge");
            change("appearance_bg_ocean");int ocean=palette();
            change("appearance_bg_paper");int paper=palette();
            if(paper==ocean||paper==canvas||ocean==canvas)throw new AssertionError("Palette colors: "+Integer.toHexString(canvas)+" / "+Integer.toHexString(ocean)+" / "+Integer.toHexString(paper));
            d.imageOfPage("21-appearance.png");
            result.putString("font_px",Float.toString(after));result.putString("default_font_px",Float.toString(before));
            result.putString("paper",Integer.toString(paper));back();back();d.await("home_recent","",6);assertNoHomeSettings();
            if(palette()!=paper)throw new AssertionError("Home canvas mismatch");
        }else{
            int paper=Integer.parseInt(d.options.getString("paper"));
            if(palette()!=paper)throw new AssertionError("Background lost across restart");
            openMySettings();tap("settings_appearance");float restored=font();
            if(Math.abs(restored-Float.parseFloat(d.options.getString("font_px")))>0.5f)throw new AssertionError("Font lost across restart");
            change("appearance_reset");if(palette()==paper)throw new AssertionError("Reset did not restore background");
            back();tap("settings_guide");tagged("guide_local");
            tap("guide_step_gateway");tap("guide_copy_adb");
            android.content.ClipboardManager clipboard=(android.content.ClipboardManager)d.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            String[] copied={""};d.runOnMainSync(()->copied[0]=clipboard.getPrimaryClip().getItemAt(0).coerceToText(d.activity).toString());
            equal("Incorrect copied command","adb reverse tcp:18744 tcp:18744",copied[0]);d.imageOfPage("21-local.png");
            tap("guide_pair");checkPair("http://127.0.0.1:18744");
            back();tap("guide_remote");edit("guide_address","http://example.invalid:18744");tap("guide_pair");
            View error=tagged("guide_error");if(((TextView)error).getText().length()==0)throw new AssertionError("Unencrypted remote endpoint not rejected");
            edit("guide_address","https://desk.example.org:18744");
            if(error.getVisibility()!=View.GONE)throw new AssertionError("Stale address validation");
            d.imageOfPage("21-remote.png");tap("guide_pair");checkPair("https://desk.example.org:18744");
            back();tagged("guide_remote");back();tap("settings_about");
            String packageName=d.activity.getPackageName();
            equal("Incorrect package",packageName,((TextView)tagged("about_package")).getText().toString());
            equal("Incorrect cache path",d.activity.getCacheDir().getAbsolutePath(),((TextView)tagged("about_cache")).getText().toString());
            equal("Incorrect files path",d.activity.getFilesDir().getAbsolutePath(),((TextView)tagged("about_files")).getText().toString());
            SystemClock.sleep(3500);d.imageOfPage("22-storage.png");tap("about_copy_prefs");
            String[] path={""};d.runOnMainSync(()->path[0]=clipboard.getPrimaryClip().getItemAt(0).coerceToText(d.activity).toString());
            equal("Incorrect copied preferences path",new java.io.File(d.activity.getDataDir(),"shared_prefs").getAbsolutePath(),path[0]);
            back();tagged("settings_guide");SystemClock.sleep(2400);d.imageOfPage("22-settings.png");back();
            d.await("home_recent","",10);assertNoHomeSettings();d.press("nav_account");tap("settings_guide");tagged("guide_local");back();tap("settings_appearance");back();
            d.await("configure","",5);back();d.await("home_recent","",5);
        }
    }
}