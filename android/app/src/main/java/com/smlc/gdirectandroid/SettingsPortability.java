package com.smlc.gdirectandroid;

import android.content.Context;
import org.json.JSONObject;

/**
 * Portable whitelist of UI/performance preferences only.
 * NO OAuth client credentials, refresh/access tokens, signing secrets,
 * private Drive metadata or short-lived Android Network handles.
 */
public final class SettingsPortability {
    public static final int SCHEMA=1;
    private SettingsPortability(){}
    public static JSONObject exportToJson(Context ctx)throws Exception {
        UserPrefs p=new UserPrefs(ctx);
        JSONObject settings=new JSONObject()
            .put("theme",p.theme())
            .put("dynamic_color",p.dynamicColor())
            .put("animations",p.animations())
            .put("haptics",p.haptics())
            .put("scan_size",p.scanSize())
            .put("scan_threads",p.scanThreads())
            .put("connections",p.connections())
            .put("cache_minutes",p.cacheMinutes())
            .put("fallback_count",p.fallbackCount())
            .put("rank_mode",p.rankMode())
            .put("download_page",p.startOnDownloads());
        return new JSONObject()
            .put("format","gdirect-android-preferences")
            .put("schema",SCHEMA)
            .put("exported_at",System.currentTimeMillis())
            .put("excludes","OAuth、Token、密钥、账号信息、任务和断点")
            .put("settings",settings);
    }
    private static int number(JSONObject j,String name,int min,int max)throws Exception {
        Object v=j.opt(name);
        if(!(v instanceof Integer||v instanceof Long))
            throw new IllegalArgumentException("字段 "+name+" 必须是整数");
        long n=((Number)v).longValue();
        if(n<min||n>max)throw new IllegalArgumentException(name+" 超出范围");
        return (int)n;
    }
    private static boolean bool(JSONObject j,String name)throws Exception {
        Object v=j.opt(name);
        if(!(v instanceof Boolean))
            throw new IllegalArgumentException("字段 "+name+" 必须为布尔值");
        return (Boolean)v;
    }
    public static void importFromJson(Context ctx,String raw)throws Exception {
        if(raw==null||raw.length()>65536)
            throw new IllegalArgumentException("JSON 配置不能超过 64 KiB");
        JSONObject root=new JSONObject(raw);
        if(!"gdirect-android-preferences".equals(root.optString("format"))
            ||root.optInt("schema")!=SCHEMA)
            throw new IllegalArgumentException("不支持的 gdirect 配置格式或版本");
        JSONObject data=root.getJSONObject("settings");
        String theme=data.getString("theme");
        if(!"system".equals(theme)&&!"light".equals(theme)&&!"dark".equals(theme))
            throw new IllegalArgumentException("不支持的外观主题");
        // Validate ALL values before changing ANY preference. Import does
        // not create OAuth credentials or restore stale Network handles.
        int scan=number(data,"scan_size",32,768);
        int threads=number(data,"scan_threads",4,64);
        int connections=number(data,"connections",1,16);
        int ttl=number(data,"cache_minutes",5,240);
        int fallback=number(data,"fallback_count",1,8);
        int rank=number(data,"rank_mode",0,1);
        boolean dynamic=bool(data,"dynamic_color");
        boolean animations=bool(data,"animations");
        boolean haptics=bool(data,"haptics");
        boolean downloadPage=bool(data,"download_page");
        UserPrefs prefs=new UserPrefs(ctx);
        if(!prefs.commitImportedSettings(theme,dynamic,animations,haptics,
                scan,threads,connections,ttl,fallback,rank,downloadPage))
            throw new IllegalStateException("配置验证成功，但无法持久保存到设备");
    }
}
