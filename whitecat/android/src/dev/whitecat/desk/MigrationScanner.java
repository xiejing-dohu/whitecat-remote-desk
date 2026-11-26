package dev.whitecat.desk;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.hardware.Camera;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.google.zxing.*;
import com.google.zxing.common.*;
import com.google.zxing.qrcode.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Single-purpose scanner. Only a Whitecat transfer code is returned to the calling Activity. */
@SuppressWarnings("deprecation")
public final class MigrationScanner extends Activity implements SurfaceHolder.Callback {
    static final int CAMERA_REQUEST=35, IMAGE_REQUEST=36;
    final ExecutorService reader=Executors.newSingleThreadExecutor();
    SurfaceView preview;
    TextView hint;
    Camera camera;
    boolean active, surfaceReady, decoding, delivered;
    long nextFrame;
    int dp(int v){return Math.round(getResources().getDisplayMetrics().density*v);}
    TextView label(String value,int sp,int color){
        TextView t=new TextView(this); t.setText(value); t.setTextSize(sp); t.setTextColor(color); t.setGravity(Gravity.CENTER); return t;
    }
    @Override protected void attachBaseContext(Context base){
        super.attachBaseContext(Appearance.context(base));
    }
    @Override public void onCreate(Bundle saved){
        setTheme(Appearance.theme(this));
        super.onCreate(saved);
        Appearance.applySystemBars(this);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Appearance.color(this,android.R.attr.windowBackground));
        root.setFitsSystemWindows(true);setContentView(root);
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setMinimumHeight(dp(60));root.addView(bar,-1,-2);
        Button back=new Button(this);back.setText("返回");back.setContentDescription("返回迁移");back.setOnClickListener(v->finish());bar.addView(back);
        TextView title=label("扫描迁移码",21,Appearance.color(this,R.attr.deskInk));bar.addView(title);
        FrameLayout frame=new FrameLayout(this);root.addView(frame,new LinearLayout.LayoutParams(-1,0,1));
        preview=new SurfaceView(this);frame.addView(preview);preview.getHolder().addCallback(this);
        View corners=new View(this){final Paint line=new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas c){
                float side=Math.min(getWidth(),getHeight())*0.72f, x=(getWidth()-side)/2,y=(getHeight()-side)/2;
                line.setColor(getResources().getColor(R.color.orca_green,getTheme()));line.setStrokeWidth(dp(3));float len=dp(23);
                for(int i=0;i<4;i++){
                    float px=(i%2==0)?x:x+side,py=(i<2)?y:y+side;
                    c.drawLine(px,py,px+((i%2==0)?len:-len),py,line);
                    c.drawLine(px,py,px,py+(i<2?len:-len),line);
                }
            }};
        frame.addView(corners);
        hint=label("将旧手机二维码置于框内",15,Appearance.color(this,R.attr.deskSecondary));hint.setPadding(dp(12),dp(14),dp(12),dp(14));root.addView(hint);
        Button image=new Button(this);image.setTag("migration_image");image.setText("从图片识别");
        image.setMinimumHeight(dp(56));LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(-1,-2);size.setMargins(dp(24),0,dp(24),dp(18));
        root.addView(image,size);image.setOnClickListener(v->{
            Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
            try{startActivityForResult(pick,IMAGE_REQUEST);}catch(ActivityNotFoundException e){hint.setText("设备没有可用的图片选择器");}
        });
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED && saved==null)
            requestPermissions(new String[]{Manifest.permission.CAMERA},CAMERA_REQUEST);
    }
    @Override protected void onResume(){super.onResume();active=true;startCamera();}
    @Override protected void onPause(){active=false;closeCamera();super.onPause();}
    @Override protected void onDestroy(){reader.shutdownNow();super.onDestroy();}
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){
        super.onRequestPermissionsResult(code,permissions,results);
        if(code==CAMERA_REQUEST){
            if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)startCamera();
            else hint.setText("未获相机权限，可从图片识别迁移码");
        }
    }
    @Override public void surfaceCreated(SurfaceHolder holder){surfaceReady=true;startCamera();}
    @Override public void surfaceChanged(SurfaceHolder holder,int format,int width,int height){}
    @Override public void surfaceDestroyed(SurfaceHolder holder){surfaceReady=false;closeCamera();}
    void startCamera(){
        if(!active||!surfaceReady||camera!=null||checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)return;
        try{
            Camera.CameraInfo info=new Camera.CameraInfo();
            int count=Camera.getNumberOfCameras(), id=0;
            for(int i=0;i<count;i++){
                Camera.getCameraInfo(i,info);if(info.facing==Camera.CameraInfo.CAMERA_FACING_BACK){id=i;break;}
            }
            Camera.getCameraInfo(id,info);
            camera=Camera.open(id);
            Camera.Parameters params=camera.getParameters();
            Camera.Size chosen=null;
            for(Camera.Size size:params.getSupportedPreviewSizes()){
                if(size.width<=1280 && size.height<=1280 && (chosen==null||size.width*size.height>chosen.width*chosen.height))chosen=size;
            }
            if(chosen!=null)params.setPreviewSize(chosen.width,chosen.height);
            List<String> focus=params.getSupportedFocusModes();
            if(focus!=null && focus.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            params.setPreviewFormat(android.graphics.ImageFormat.NV21);
            camera.setParameters(params);
            int deg=getWindowManager().getDefaultDisplay().getRotation()*90;
            int orientation=info.facing==Camera.CameraInfo.CAMERA_FACING_FRONT?(360-(info.orientation+deg)%360)%360:(info.orientation-deg+360)%360;
            camera.setDisplayOrientation(orientation);camera.setPreviewDisplay(preview.getHolder());
            Camera.Size size=camera.getParameters().getPreviewSize();
            camera.setPreviewCallback((bytes,source)->{
                if(decoding||delivered||!active||SystemClock.elapsedRealtime()<nextFrame||bytes==null)return;
                decoding=true;nextFrame=SystemClock.elapsedRealtime()+220;
                byte[] y=Arrays.copyOf(bytes,size.width*size.height);
                reader.execute(()->{
                    String code=decode(new PlanarYUVLuminanceSource(y,size.width,size.height,0,0,size.width,size.height,false));
                    runOnUiThread(()->{decoding=false;if(active && source==camera && code!=null)accept(code);});
                });
            });
            camera.startPreview();
        }catch(Exception e){closeCamera();hint.setText("相机暂不可用，可从图片识别迁移码");}
    }
    void closeCamera(){
        if(camera==null)return;
        try{camera.setPreviewCallback(null);camera.stopPreview();}catch(RuntimeException ignored){}
        camera.release();camera=null;
    }
    static String decode(LuminanceSource pixels){
        try{
            Map<DecodeHintType,Object> hints=new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.TRY_HARDER,Boolean.TRUE);
            return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(pixels)),hints).getText();
        }catch(ReaderException|RuntimeException e){return null;}
    }
    void accept(String value){
        if(delivered||isFinishing()||isDestroyed())return;
        if(!MigrationPage.validCode(value)){hint.setText("这张二维码不是白猫迁移码");return;}
        delivered=true;
        setResult(RESULT_OK,new Intent().putExtra("migration",value));finish();
    }
    @Override protected void onActivityResult(int r,int result,Intent data){
        super.onActivityResult(r,result,data);
        if(r!=IMAGE_REQUEST||result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();hint.setText("正在识别图片…");
        reader.execute(()->{
            String code=null;
            try{
                BitmapFactory.Options opt=new BitmapFactory.Options();opt.inJustDecodeBounds=true;
                try(InputStream in=getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(in,null,opt);}
                if(opt.outWidth<=0||opt.outHeight<=0)throw new IOException();
                opt.inSampleSize=1;
                while(Math.max(opt.outWidth,opt.outHeight)/opt.inSampleSize>2048)opt.inSampleSize*=2;
                opt.inJustDecodeBounds=false;opt.inPreferredConfig=Bitmap.Config.ARGB_8888;
                Bitmap bmp;
                try(InputStream in=getContentResolver().openInputStream(uri)){bmp=BitmapFactory.decodeStream(in,null,opt);}
                if(bmp==null)throw new IOException();
                try{
                    int w=bmp.getWidth(),h=bmp.getHeight();int[] rgba=new int[w*h];bmp.getPixels(rgba,0,w,0,0,w,h);
                    code=decode(new RGBLuminanceSource(w,h,rgba));
                }finally{bmp.recycle();}
            }catch(Exception ignored){}
            final String value=code;
            runOnUiThread(()->{if(!isDestroyed()&&!isFinishing()){
                if(value==null)hint.setText("没有识别到二维码，请选择清晰的迁移码图片");
                else accept(value);
            }});
        });
    }
}
