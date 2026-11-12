package com.smlc.gdirectandroid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.DocumentsContract;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Durable user-managed FIFO queue over the original single-session aria2 JNI.
 *
 * Multiple separate files can wait and be paused/resumed across restarts;
 * ONE aria2 file is active at a time, with up to 16 internal connections.
 * State and progress survive process death in SQLite; .aria2 bitfields and
 * partial payloads live in app-private filesDir, never disposable cacheDir.
 * Access tokens are refreshed in RAM per job and never persisted to SQLite,
 * Intent extras, files, notifications or history.
 */
public final class TransferService extends Service {
    public static final String ACTION_START="com.smlc.gdirectandroid.QUEUE";
    public static final String ACTION_CONTROL="com.smlc.gdirectandroid.CONTROL";
    public static final String COMMAND_PAUSE="pause", COMMAND_CANCEL="cancel",
        COMMAND_RESUME="resume", COMMAND_REMOVE="remove";
    public static final String COMMAND_RESUME_ALL="resume_all";
    public static final String COMMAND_PRIORITY="priority";
    private static final String CHANNEL="gdirect_transfers";
    private static final int NOTIFY_ID=9041;
    private static final Pattern COUNTS=Pattern.compile("(\\d+)/(\\d+)");
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    // SQLite writes must not interrupt Material 3 / 60Hz UI animations.
    private final ExecutorService reporter=Executors.newSingleThreadExecutor();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private TransferQueue jobs;
    private DesktopOAuth credentials;
    private boolean working;
    private volatile String activeId;
    private volatile String requestedStop; // pause / cancel, no auth data
    private volatile boolean timedOut;
    private volatile boolean closing;
    private Runnable progressTick;
    private static volatile Snapshot current =
        new Snapshot("idle","","任务列表已就绪",0L,-1L,"",0L);

    public static final class Snapshot {
        public final String status,name,detail,id;
        public final long done,total,changedAt;
        Snapshot(String status,String name,String detail,long done,long total,
                 String id,long changedAt) {
            this.status=status;this.name=name;this.detail=detail;
            this.done=done;this.total=total;this.id=id;this.changedAt=changedAt;
        }
        public int percent() {
            if(total<=0)return -1;
            return (int)Math.max(0,Math.min(100,done*100.0/total));
        }
    }
    public static Snapshot snapshot(){return current;}
    public static JSONArray tasks(Context ctx) {
        try(TransferQueue store=new TransferQueue(ctx.getApplicationContext())) {
            return store.list();
        }catch(Exception e){return new JSONArray();}
    }
    // Backwards-compatible history API used by the initial RC UI.
    public static JSONArray history(Context ctx){return tasks(ctx);}
    public static void clearHistory(Context ctx) {
        try(TransferQueue store=new TransferQueue(ctx.getApplicationContext())){
            store.clearFinished();
        }
    }
    public static Intent create(Context context,Uri destination,DrivePanel.DriveFile file,
                                long handle,String route,String ips,int connections) {
        Intent i=new Intent(context,TransferService.class).setAction(ACTION_START);
        i.putExtra("destination",destination.toString());
        i.putExtra("file_id",file.id);
        i.putExtra("name",file.name);
        i.putExtra("md5",file.md5);
        i.putExtra("size",file.size);
        i.putExtra("handle",handle);
        i.putExtra("route",route);
        i.putExtra("ips",ips);
        i.putExtra("connections",connections);
        // The task ID is generated and committed inside TransferQueue.add.
        // Crucially: no OAuth token is ever carried in a replayable Intent.
        return i;
    }
    public static Intent control(Context context,String command,String id,
                                 long handle,String route,String ips) {
        Intent i=new Intent(context,TransferService.class).setAction(ACTION_CONTROL);
        i.putExtra("command",command);
        i.putExtra("job",id);
        i.putExtra("handle",handle);
        i.putExtra("route",route);
        i.putExtra("ips",ips);
        return i;
    }

    @Override public void onCreate(){
        super.onCreate();
        jobs=new TransferQueue(this);
        // Recover tasks interrupted by process death once before any new
        // session starts. Never automatically start a background FGS on boot.
        jobs.getWritableDatabase();
        credentials=new DesktopOAuth(getApplicationContext());
        NotificationChannel c=new NotificationChannel(CHANNEL,
            "gdirect 下载任务",NotificationManager.IMPORTANCE_LOW);
        c.setDescription("正在进行的下载、暂停与任务队列");
        getSystemService(NotificationManager.class).createNotificationChannel(c);
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null){stopSelf();return START_NOT_STICKY;}
        boolean shouldRun=false;
        try {
            if(ACTION_START.equals(intent.getAction())){
                String id=intent.getStringExtra("file_id");
                String name=intent.getStringExtra("name");
                String destination=intent.getStringExtra("destination");
                String ips=intent.getStringExtra("ips");
                long handle=intent.getLongExtra("handle",-1);
                if(handle<0)throw new IllegalArgumentException("网络句柄不可用");
                Uri uri=Uri.parse(destination);
                if(!"content".equals(uri.getScheme()))
                    throw new IllegalArgumentException("仅支持 Android SAF 保存位置");
                TransferQueue.Job queued=jobs.add(id,name,intent.getStringExtra("md5"),
                    uri.toString(),intent.getStringExtra("route"),ips,handle,
                    intent.getLongExtra("size",-1),intent.getIntExtra("connections",8));
                current=new Snapshot("queued",queued.name,"已加入下载队列",
                    0,queued.size,queued.id,System.currentTimeMillis());
                shouldRun=true;
            }else if(ACTION_CONTROL.equals(intent.getAction())){
                String command=intent.getStringExtra("command");
                String id=intent.getStringExtra("job");
                if(COMMAND_PAUSE.equals(command)||COMMAND_CANCEL.equals(command)){
                    if(id!=null&&id.equals(activeId)) {
                        requestedStop=command;
                        MainActivity.nativeCancelDownload();
                    }else if(COMMAND_PAUSE.equals(command)){
                        jobs.move(id,TransferQueue.QUEUED,TransferQueue.PAUSED);
                    }else{
                        if(jobs.move(id,TransferQueue.QUEUED,TransferQueue.CANCELLED)
                          || jobs.move(id,TransferQueue.PAUSED,TransferQueue.CANCELLED)
                          || jobs.move(id,TransferQueue.FAILED,TransferQueue.CANCELLED))
                            discard(id);
                    }
                }else if(COMMAND_RESUME.equals(command)||
                          COMMAND_RESUME_ALL.equals(command)){
                    long handle=intent.getLongExtra("handle",-1);
                    String route=intent.getStringExtra("route");
                    String ips=intent.getStringExtra("ips");
                    if(handle<0||ips==null||ips.isEmpty()){
                        throw new IllegalArgumentException("请选择有效网络及 Google 优选 IP");
                    }
                    if(COMMAND_RESUME.equals(command)){
                        shouldRun=jobs.resume(id,handle,route,ips);
                    }else{
                        // All eligible jobs, including those beyond the UI page.
                        shouldRun=jobs.resumeAll(handle,route,ips)>0;
                    }
                }else if(COMMAND_PRIORITY.equals(command)){
                    jobs.prioritize(id);
                }else if(COMMAND_REMOVE.equals(command)){
                    if(id!=null&&id.equals(activeId)){
                        // Do not unlink a partial while libaria2 still owns it.
                        requestedStop=COMMAND_CANCEL;
                        MainActivity.nativeCancelDownload();
                    }else if(jobs.remove(id)){
                        discard(id);
                    }
                }
            }else{
                stopSelfResult(startId);
                return START_NOT_STICKY;
            }
            if(shouldRun&&!working)startQueue();
            else if(!working)stopSelfResult(startId);
        }catch(Exception|LinkageError error){
            current=new Snapshot("failed","","无法加入队列："+safe(error.getMessage()),
                0,-1,"",System.currentTimeMillis());
            if(!working)stopSelfResult(startId);
        }
        return START_NOT_STICKY;
    }

    private void startQueue(){
        if(working||closing)return;
        working=true;
        timedOut=false;
        try {
            if(Build.VERSION.SDK_INT>=29)
                startForeground(NOTIFY_ID,notification(false),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(NOTIFY_ID,notification(false));
        }catch(RuntimeException error){
            working=false;
            TransferQueue.Job first=jobs.next();
            if(first!=null)jobs.change(first.id,TransferQueue.QUEUED,
                TransferQueue.PAUSED,"系统不允许启动前台下载服务；请在前台手动恢复");
            stopSelf();
            return;
        }
        progressTick=new Runnable(){
            @Override public void run(){
                if(!working)return;
                String id=activeId;
                if(id!=null){
                    if(requestedStop!=null)MainActivity.nativeCancelDownload();
                    try {
                        String raw=MainActivity.nativeDownloadProgress();
                        Matcher match=COUNTS.matcher(raw);
                        if(match.find()){
                            long done=Long.parseLong(match.group(1));
                            long total=Long.parseLong(match.group(2));
                            Snapshot before=current;
                            current=new Snapshot("running",before.name,raw,done,total,id,
                                System.currentTimeMillis());
                            reporter.execute(()->jobs.progress(id,done,total,raw));
                        }
                    }catch(Exception|LinkageError ignored){}
                }
                notifyProgress(false);
                ui.postDelayed(this,1500L);
            }
        };
        ui.post(progressTick);
        worker.execute(this::drainQueue);
    }

    private void drainQueue(){
        while(!timedOut){
            TransferQueue.Job job=jobs.next();
            if(job==null)break;
            if(!jobs.change(job.id,TransferQueue.QUEUED,
                TransferQueue.RUNNING,"正在准备 Google Drive 下载"))continue;
            activeId=job.id;
            requestedStop=null;
            current=new Snapshot("running",job.name,"正在准备文件…",job.done,
                job.size,job.id,System.currentTimeMillis());
            try {
                // No OAuth token survives in the task database. Each file gets
                // a fresh valid access token from Keystore-encrypted refresh.
                ensureNetwork(job.network);
                String token=credentials.getTokenBlocking();
                long bytes=transfer(job,token);
                String stop=requestedStop;
                if(COMMAND_PAUSE.equals(stop) || (timedOut && !COMMAND_CANCEL.equals(stop))) {
                    jobs.change(job.id,TransferQueue.RUNNING,
                        TransferQueue.PAUSED,"下载已暂停，断点保留");
                    current=new Snapshot("paused",job.name,"已保留本地断点",
                        job.done,job.size,job.id,System.currentTimeMillis());
                }else if(COMMAND_CANCEL.equals(stop)){
                    jobs.change(job.id,TransferQueue.RUNNING,
                        TransferQueue.CANCELLED,"已取消下载");
                    discard(job.id);
                    current=new Snapshot("cancelled",job.name,"任务已取消",
                        0,job.size,job.id,System.currentTimeMillis());
                }else{
                    jobs.change(job.id,TransferQueue.RUNNING,
                        TransferQueue.COMPLETED,"下载完成 · "+displaySize(bytes));
                    current=new Snapshot("completed",job.name,
                        "已完整保存 · "+displaySize(bytes),bytes,bytes,job.id,
                        System.currentTimeMillis());
                    discard(job.id);
                }
            }catch(Exception|LinkageError error){
                String stop=requestedStop;
                String state=COMMAND_CANCEL.equals(stop)?TransferQueue.CANCELLED:
                    (timedOut||COMMAND_PAUSE.equals(stop))?TransferQueue.PAUSED:
                    TransferQueue.FAILED;
                String reason=TransferQueue.PAUSED.equals(state)?
                    "已暂停；可重新选择网络继续":
                    TransferQueue.CANCELLED.equals(state)?"用户取消":
                    safe(error.getMessage());
                jobs.change(job.id,TransferQueue.RUNNING,state,reason);
                if(TransferQueue.CANCELLED.equals(state))discard(job.id);
                current=new Snapshot(state,job.name,reason,
                    job.done,job.size,job.id,System.currentTimeMillis());
            }finally{
                activeId=null;
                requestedStop=null;
                notifyProgress(false);
            }
        }
        ui.post(()->{
            // Protect against a last enqueue being delivered while the
            // worker is winding down. It will see the next explicit command.
            working=false;
            if(progressTick!=null)ui.removeCallbacks(progressTick);
            if(closing)return; // Never restart a destroyed service.
            // A new user-enqueued job can arrive in the small gap after
            // the previous loop saw an empty queue. Never strand it.
            if(!timedOut && jobs.next()!=null){
                startQueue();
            }else{
                stopForeground(STOP_FOREGROUND_REMOVE);
                notifyProgress(true);
                stopSelf();
            }
        });
    }

    private void ensureNetwork(long handle)throws Exception {
        if(handle==0)return; // user expressly selected system default
        ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        for(Network network:cm.getAllNetworks()){
            if(network.getNetworkHandle()==handle)return;
        }
        throw new Exception("原选择的网络已断开，请在线路页面重新选择并恢复");
    }

    private long transfer(TransferQueue.Job job,String token)throws Exception {
        File folder=folder();
        File result=payload(job.id);
        File checkpoint=controlFile(job.id);
        File ca=new File(getNoBackupFilesDir(),"gts-roots.pem");
        if(!ca.isFile()||ca.length()<1000){
            try(InputStream in=getAssets().open("gts-roots.pem");
                OutputStream out=new FileOutputStream(ca)){
                byte[] buf=new byte[8192];int n;
                while((n=in.read(buf))!=-1)out.write(buf,0,n);
            }
        }
        if(requestedStop!=null)throw new Exception("用户暂停或取消");
        boolean completeLocally=result.isFile() && !checkpoint.isFile()
            && job.size>0 && result.length()==job.size && !job.md5.isEmpty()
            && validMd5(result,job.md5);
        if(!completeLocally){
            String report=MainActivity.nativeDownloadDrive(job.fileId,token,
                ca.getAbsolutePath(),folder.getAbsolutePath(),result.getName(),
                job.network,job.ips,job.connections);
            if(!report.startsWith("OK\n"))
                throw new Exception("aria2 下载未完成：" + safe(report));
        }
        if(requestedStop!=null)throw new Exception("用户暂停或取消");
        long size=result.length();
        if(size<=0)throw new Exception("下载得到空文件");
        if(job.size>=0&&size!=job.size)throw new Exception("文件大小不符，断点已保留");
        if(!job.md5.isEmpty()&&!validMd5(result,job.md5)){
            throw new Exception("Drive MD5 不匹配，拒绝保存；请尝试重启任务");
        }
        Uri uri=Uri.parse(job.uri);
        current=new Snapshot("saving",job.name,"已验证文件，正在保存到目标位置",
            size,size,job.id,System.currentTimeMillis());
        notifyProgress(false);
        long copied=0;
        try(InputStream in=new FileInputStream(result);
            OutputStream out=getContentResolver().openOutputStream(uri,"wt")){
            if(out==null)throw new Exception("文档提供程序拒绝写入");
            byte[] buffer=new byte[65536];
            int n;
            while((n=in.read(buffer))!=-1){
                if(requestedStop!=null||timedOut)
                    throw new Exception("用户中断保存；目标文件可能未完整");
                out.write(buffer,0,n);copied+=n;
            }
            out.flush();
        }catch(Exception error){
            throw new Exception("SAF 保存失败，目标可能不完整；已保留本地完整文件："
                +safe(error.getMessage()));
        }
        if(copied!=size)throw new Exception("保存长度校验失败");
        return size;
    }

    private static boolean validMd5(File source,String expected)throws Exception {
        MessageDigest md5=MessageDigest.getInstance("MD5");
        try(InputStream in=new FileInputStream(source)){
            byte[] data=new byte[65536];int n;
            while((n=in.read(data))!=-1)md5.update(data,0,n);
        }
        StringBuilder hex=new StringBuilder();
        for(byte value:md5.digest())hex.append(
            String.format(Locale.ROOT,"%02x",value&0xff));
        return hex.toString().equalsIgnoreCase(expected);
    }
    private File folder()throws Exception {
        File location=new File(getFilesDir(),"transfers");
        if(!location.isDirectory()&&!location.mkdirs())
            throw new Exception("无法创建持久化下载目录");
        return location;
    }
    private File payload(String id) {
        return new File(new File(getFilesDir(),"transfers"),id+".bin");
    }
    private File controlFile(String id) {
        return new File(new File(getFilesDir(),"transfers"),id+".bin.aria2");
    }
    private void discard(String id){
        if(id==null||!id.matches("[0-9a-f-]{36}"))return;
        File f=payload(id),ctrl=controlFile(id);
        if(f.exists()&&!f.delete())f.deleteOnExit();
        if(ctrl.exists()&&!ctrl.delete())ctrl.deleteOnExit();
    }
    private Notification notification(boolean finished){
        Snapshot s=current;
        Intent open=new Intent(this,MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("open_tab","transfers");
        PendingIntent pending=PendingIntent.getActivity(this,9021,open,
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b=new NotificationCompat.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(finished?"gdirect · 下载队列":s.name.isEmpty()?"gdirect 下载":s.name)
            .setContentText(s.detail).setOnlyAlertOnce(true).setContentIntent(pending)
            .setOngoing(!finished).setVisibility(NotificationCompat.VISIBILITY_PRIVATE);
        if(!finished){
            if(s.percent()>=0)b.setProgress(100,s.percent(),false);
            else b.setProgress(0,0,true);
            if(activeId!=null) {
                Intent pause=control(this,COMMAND_PAUSE,activeId,-1,null,null);
                PendingIntent pi=PendingIntent.getService(this,9022,pause,
                    PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
                b.addAction(android.R.drawable.ic_media_pause,"暂停",pi);
            }
        }else b.setAutoCancel(true).setProgress(0,0,false);
        return b.build();
    }
    private void notifyProgress(boolean finished){
        getSystemService(NotificationManager.class).notify(NOTIFY_ID,notification(finished));
    }
    private static String safe(String raw){
        if(raw==null||raw.isEmpty())return "操作失败，请检查网络和账号授权";
        // Avoid echoing the raw aria2 report if it contains headers/URLs.
        String result=raw.replaceAll("(?i)Bearer\\s+[^\\s]+","Bearer [REDACTED]");
        return result.length()>220?result.substring(0,220):result;
    }
    private static String displaySize(long bytes){
        if(bytes<1024)return bytes+" B";
        if(bytes<1048576)return String.format(Locale.ROOT,"%.1f KiB",bytes/1024.0);
        return String.format(Locale.ROOT,"%.1f MiB",bytes/1048576.0);
    }

    @Override public void onTimeout(int startId,int fgsType) {
        timedOut=true;
        if(requestedStop==null)requestedStop=COMMAND_PAUSE;
        MainActivity.nativeCancelDownload();
        String id=activeId;
        if(id!=null)jobs.change(id,TransferQueue.RUNNING,TransferQueue.PAUSED,
            "系统前台服务已达到时长上限，请稍后手动恢复");
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf(startId);
    }
    @Override public void onDestroy(){
        closing=true;
        timedOut=true;
        if(activeId!=null) {
            // A previous cancel must not accidentally become a pause.
            if(requestedStop==null)requestedStop=COMMAND_PAUSE;
            MainActivity.nativeCancelDownload();
        }
        if(progressTick!=null)ui.removeCallbacks(progressTick);
        // libaria2 may still be unwinding. Cleanup waits for the native worker;
        // SQLite closes only after already queued progress writes have drained.
        worker.execute(()->{
            credentials.shutdown();
            reporter.execute(jobs::close);
            reporter.shutdown();
        });
        worker.shutdown();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
