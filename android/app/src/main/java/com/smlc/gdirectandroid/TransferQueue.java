package com.smlc.gdirectandroid;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;

/**
 * Durable, app-private, token-free job journal.
 * SQLite transactions serialize enqueue / state / progress changes. Jobs are
 * deliberately NOT auto-started on boot: Android FGS start restrictions and
 * ephemeral Network handles require an explicit user resume.
 */
public final class TransferQueue extends SQLiteOpenHelper {
    private static final String DATABASE = "gdirect-transfers-v2.sqlite";
    private static final int VERSION = 2;
    private static final String TABLE = "jobs";
    private static final int MAX_JOBS = 500;
    private static final java.util.concurrent.atomic.AtomicBoolean RECOVERED =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    public static final String QUEUED="queued", RUNNING="running",
        PAUSED="paused", FAILED="failed", COMPLETED="completed", CANCELLED="cancelled";
    public static final class Job {
        public String id, fileId, name, md5, uri, route, ips, state, detail;
        public long size, network, done, total, created, updated;
        public int connections;
        public boolean multiIp;
        public boolean terminal() { return COMPLETED.equals(state)||CANCELLED.equals(state); }
        public JSONObject toJson() throws Exception {
            return new JSONObject()
                .put("id",id).put("name",name).put("state",state)
                .put("size",size).put("done",done).put("total",total)
                .put("route",route).put("detail",detail).put("multi_ip",multiIp)
                .put("created",created).put("updated",updated);
        }
    }
    public TransferQueue(Context context) { super(context, DATABASE, null, VERSION); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE jobs ("
            +"id TEXT PRIMARY KEY, file_id TEXT NOT NULL, name TEXT NOT NULL,"
            +"md5 TEXT NOT NULL, uri TEXT NOT NULL, route TEXT NOT NULL,"
            +"ips TEXT NOT NULL, network INTEGER NOT NULL, connections INTEGER NOT NULL,"
            +"size INTEGER NOT NULL, multi_ip INTEGER NOT NULL DEFAULT 0,"
            +"state TEXT NOT NULL, detail TEXT NOT NULL,"
            +"done INTEGER NOT NULL, total INTEGER NOT NULL, created INTEGER NOT NULL,"
            +"updated INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX jobs_state_created ON jobs (state, created)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) {
        // No destructive migration: future upgrades must preserve all task
        // metadata and associated .aria2 partial files.
        if(oldVersion==1 && newVersion==2) {
            db.execSQL("ALTER TABLE jobs ADD COLUMN multi_ip INTEGER NOT NULL DEFAULT 0");
            return;
        }
        throw new IllegalStateException("不支持的任务数据库版本："+oldVersion+" -> "+newVersion);
    }
    @Override public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        // A process death interrupted the previous foreground worker; users
        // must explicitly resume after verifying network/OAuth connectivity.
        if (!RECOVERED.compareAndSet(false,true)) return;
        ContentValues interrupted=new ContentValues();
        interrupted.put("state",PAUSED);
        interrupted.put("detail","应用曾中断，请确认网络后继续（断点仍保留）");
        interrupted.put("updated",System.currentTimeMillis());
        db.update(TABLE,interrupted,"state=?",new String[]{RUNNING});
    }
    private static Job read(Cursor c) {
        Job j=new Job();
        j.id=c.getString(c.getColumnIndexOrThrow("id"));
        j.fileId=c.getString(c.getColumnIndexOrThrow("file_id"));
        j.name=c.getString(c.getColumnIndexOrThrow("name"));
        j.md5=c.getString(c.getColumnIndexOrThrow("md5"));
        j.uri=c.getString(c.getColumnIndexOrThrow("uri"));
        j.route=c.getString(c.getColumnIndexOrThrow("route"));
        j.ips=c.getString(c.getColumnIndexOrThrow("ips"));
        j.state=c.getString(c.getColumnIndexOrThrow("state"));
        j.detail=c.getString(c.getColumnIndexOrThrow("detail"));
        j.size=c.getLong(c.getColumnIndexOrThrow("size"));
        j.network=c.getLong(c.getColumnIndexOrThrow("network"));
        j.done=c.getLong(c.getColumnIndexOrThrow("done"));
        j.total=c.getLong(c.getColumnIndexOrThrow("total"));
        j.connections=c.getInt(c.getColumnIndexOrThrow("connections"));
        j.multiIp=c.getInt(c.getColumnIndexOrThrow("multi_ip"))!=0;
        j.created=c.getLong(c.getColumnIndexOrThrow("created"));
        j.updated=c.getLong(c.getColumnIndexOrThrow("updated"));
        return j;
    }
    public synchronized Job add(String fileId,String name,String md5,String uri,
                                String route,String ips,long network,long size,int connections) {
        return add(fileId,name,md5,uri,route,ips,network,size,connections,false);
    }
    public synchronized Job add(String fileId,String name,String md5,String uri,
                                String route,String ips,long network,long size,int connections,
                                boolean multiIp) {
        if (fileId==null || !fileId.matches("[A-Za-z0-9_-]{1,256}")
            || uri==null || !uri.startsWith("content://") || ips==null || ips.isEmpty())
            throw new IllegalArgumentException("下载参数不完整");
        SQLiteDatabase db=getWritableDatabase();
        try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM "+TABLE,null)) {
            if(c.moveToFirst()&&c.getInt(0)>=MAX_JOBS)
                throw new IllegalStateException("已达到 500 项记录上限，请先清理已完成任务");
        }
        Job j=new Job();
        j.id=UUID.randomUUID().toString();
        j.fileId=fileId; j.name=name==null?fileId:name;
        j.md5=md5==null?"":md5;
        j.uri=uri; j.route=route==null?"所选网络":route; j.ips=ips;
        j.network=network; j.size=size; j.connections=Math.max(1,Math.min(connections,16));
        j.multiIp=multiIp;
        j.state=QUEUED; j.detail="等待开始"; j.done=0; j.total=size;
        j.created=j.updated=System.currentTimeMillis();
        ContentValues values=new ContentValues();
        values.put("id",j.id);values.put("file_id",j.fileId);values.put("name",j.name);
        values.put("md5",j.md5);values.put("uri",j.uri);values.put("route",j.route);
        values.put("ips",j.ips);values.put("network",j.network);
        values.put("size",j.size);values.put("connections",j.connections);
        values.put("multi_ip",j.multiIp?1:0);
        values.put("state",j.state);values.put("detail",j.detail);
        values.put("done",0);values.put("total",j.total);
        values.put("created",j.created);values.put("updated",j.updated);
        if(db.insertOrThrow(TABLE,null,values)<0)throw new IllegalStateException("无法写入任务");
        return j;
    }
    public synchronized Job get(String id) {
        if(id==null)return null;
        try(Cursor c=getReadableDatabase().query(TABLE,null,"id=?",
            new String[]{id},null,null,null,"1")) {
            return c.moveToFirst()?read(c):null;
        }
    }
    public synchronized Job next() {
        try(Cursor c=getReadableDatabase().query(TABLE,null,"state=?",
            new String[]{QUEUED},null,null,"created ASC","1")) {
            return c.moveToFirst()?read(c):null;
        }
    }
    public synchronized JSONArray list() {
        JSONArray result=new JSONArray();
        try(Cursor c=getReadableDatabase().query(TABLE,null,null,null,null,null,
            "CASE state WHEN 'running' THEN 0 WHEN 'queued' THEN 1 "
            +"WHEN 'paused' THEN 2 WHEN 'failed' THEN 3 ELSE 4 END,"
            +"CASE WHEN state='queued' THEN created END ASC,updated DESC",null)) {
            while(c.moveToNext()) {
                try {result.put(read(c).toJson());}catch(Exception ignored) {}
            }
        }
        return result;
    }
    public synchronized boolean change(String id,String expected,String state,String detail) {
        ContentValues values=new ContentValues();
        values.put("state",state);
        values.put("detail",detail==null?"":detail);
        values.put("updated",System.currentTimeMillis());
        String where="id=?";
        String[] args={id};
        if(expected!=null){where+=" AND state=?";args=new String[]{id,expected};}
        return getWritableDatabase().update(TABLE,values,where,args)==1;
    }
    public synchronized void progress(String id,long done,long total,String detail) {
        ContentValues cv=new ContentValues();
        cv.put("done",Math.max(0,done));
        cv.put("total",total);
        cv.put("detail",detail==null?"":detail);
        cv.put("updated",System.currentTimeMillis());
        getWritableDatabase().update(TABLE,cv,"id=? AND state=?",
            new String[]{id,RUNNING});
    }
    /**
     * Resume/rebind every queued, paused and failed file in one atomic update.
     * This deliberately bypasses the paged UI and preserves created/FIFO order.
     */
    public synchronized int resumeAll(long handle,String route,String ips) {
        if(handle<0||ips==null||ips.isEmpty())return 0;
        ContentValues values=new ContentValues();
        values.put("state",QUEUED);
        values.put("network",handle);
        values.put("route",route==null?"所选网络":route);
        values.put("ips",ips);
        values.put("detail","已排队（保留可用的 .aria2 断点）");
        values.put("updated",System.currentTimeMillis());
        return getWritableDatabase().update(TABLE,values,"state IN (?,?,?)",
            new String[]{QUEUED,PAUSED,FAILED});
    }

    /** Final state and byte counters must be committed together so the
     * durable transfer card never shows "completed" with stale 0/0 bytes. */
    public synchronized boolean complete(String id,long bytes) {
        if(id==null||bytes<=0)return false;
        ContentValues values=new ContentValues();
        values.put("state",COMPLETED);
        values.put("done",bytes);
        values.put("total",bytes);
        values.put("detail","下载完成 · "+String.format(
            java.util.Locale.ROOT,"%.1f MiB",bytes/1048576.0));
        values.put("updated",System.currentTimeMillis());
        return getWritableDatabase().update(TABLE,values,
            "id=? AND state=?",new String[]{id,RUNNING})==1;
    }

    /** Move a queued job to the front; does not interrupt the current file. */
    public synchronized boolean prioritize(String id) {
        Job selected=get(id);
        if(selected==null||!QUEUED.equals(selected.state))return false;
        long earliest=System.currentTimeMillis();
        try(Cursor c=getReadableDatabase().rawQuery(
            "SELECT MIN(created) FROM "+TABLE+" WHERE state=?",
            new String[]{QUEUED})){
            if(c.moveToFirst()&&!c.isNull(0))earliest=c.getLong(0);
        }
        ContentValues cv=new ContentValues();
        cv.put("created",earliest-1);
        cv.put("updated",System.currentTimeMillis());
        return getWritableDatabase().update(TABLE,cv,"id=? AND state=?",
            new String[]{id,QUEUED})==1;
    }
    public synchronized boolean resume(String id,long network,String route,String ips) {
        Job j=get(id);
        if(j==null || !(PAUSED.equals(j.state)||FAILED.equals(j.state)))return false;
        if(network<0||ips==null||ips.isEmpty())return false;
        ContentValues cv=new ContentValues();
        cv.put("state",QUEUED);
        cv.put("network",network);cv.put("route",route);
        cv.put("ips",ips);cv.put("detail","已排队恢复（保留本地断点）");
        cv.put("updated",System.currentTimeMillis());
        return getWritableDatabase().update(TABLE,cv,"id=?",new String[]{id})==1;
    }
    public synchronized boolean move(String id,String expected,String newState) {
        return change(id,expected,newState,
            PAUSED.equals(newState)?"暂停，已保留 .aria2 断点":
            CANCELLED.equals(newState)?"已取消":newState);
    }
    public synchronized boolean remove(String id) {
        if (id==null) return false;
        Job task=get(id);
        if(task==null || RUNNING.equals(task.state)) return false;
        return getWritableDatabase().delete(TABLE,"id=?",new String[]{id})==1;
    }
    /** A record-delete action must never remove queued or live payloads. */
    public synchronized boolean removeFinishedRecord(String id) {
        if(id==null||id.isEmpty())return false;
        return getWritableDatabase().delete(TABLE,
            "id=? AND state IN (?,?)",
            new String[]{id,COMPLETED,CANCELLED})==1;
    }
    public synchronized int clearFinished() {
        return getWritableDatabase().delete(TABLE,"state IN (?,?)",
            new String[]{COMPLETED,CANCELLED});
    }
}
