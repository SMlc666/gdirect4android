package com.smlc.gdirectandroid;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Persistent, per-Android-Network VERIFIED Google endpoint history.
 * History survives process death and app restart. Only results younger than
 * the configurable TTL and bound to the SAME live Network handle may be used
 * to connect. Android Network handles are not persistent across reboot:
 * an old scan never silently authorizes a new Wi-Fi/mobile/VPN path.
 *
 * No fabricated/bundled "previously verified" endpoints and no OAuth data.
 */
public final class EndpointPool {
    private final SharedPreferences storage;
    private final UserPrefs userPrefs;
    static final String STORAGE_FILE="gdirect-google-ip-rank-v1";

    static final class Candidate {
        final String ip;
        final long latency;
        final double speed;
        final long benchmarkBytes;
        Candidate(String ip,long latency,double speed,long benchmarkBytes) {
            this.ip=ip;
            this.latency=Math.max(1,latency);
            this.speed=Double.isFinite(speed)?Math.max(0,speed):0;
            this.benchmarkBytes=Math.max(0,benchmarkBytes);
        }
        String subnet() {
            int last=ip.lastIndexOf('.');
            return last>0?ip.substring(0,last):ip;
        }
    }
    public EndpointPool(Context context) {
        storage=context.getSharedPreferences(STORAGE_FILE,Context.MODE_PRIVATE);
        userPrefs=new UserPrefs(context);
    }
    private static boolean validIp(String ip) {
        if(ip==null||!ip.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"))return false;
        String[] parts=ip.split("\\.");
        for(String part:parts) {
            int n=Integer.parseInt(part);
            if(n>255)return false;
        }
        return true;
    }
    private String key(long handle){return "route-"+handle;}
    private String preferenceKey(long handle){return "manual-"+handle;}

    private JSONObject load(long handle,boolean onlyFresh) {
        if(handle<0)return null;
        String raw=storage.getString(key(handle),null);
        if(raw==null)return null;
        try {
            JSONObject record=new JSONObject(raw);
            if(record.optLong("network_handle",-1L)!=handle)return null;
            long elapsed=System.currentTimeMillis()-record.optLong("saved_at",0);
            if(elapsed<0)return null;
            if(onlyFresh && elapsed>userPrefs.cacheMinutes()*60_000L)return null;
            return record;
        }catch(Exception invalid){return null;}
    }
    private List<Candidate> candidates(long handle,boolean onlyFresh) {
        List<Candidate> found=new ArrayList<>();
        JSONObject record=load(handle,onlyFresh);
        JSONArray values=record==null?null:record.optJSONArray("winners");
        if(values==null)return found;
        Set<String> seen=new HashSet<>();
        for(int i=0;i<values.length();i++){
            JSONObject item=values.optJSONObject(i);
            if(item==null)continue;
            String ip=item.optString("ip","");
            if(!validIp(ip)||!seen.add(ip))continue;
            found.add(new Candidate(ip,
                item.optLong("latency_ms",999999L),
                item.optDouble("speed_kib_s",0.0),
                item.optLong("benchmark_bytes",0L)));
        }
        return found;
    }
    /** Returns 0 without replacing the previous history on a cancelled scan. */
    public synchronized int accept(long networkHandle,JSONObject scan)throws Exception {
        if(networkHandle<0)throw new IllegalArgumentException("网络句柄无效");
        if(scan.has("error"))throw new Exception(scan.optString("error"));
        if(scan.optBoolean("cancelled")||scan.optInt("success")<=0)return 0;
        if(scan.optLong("network_handle",-1)!=networkHandle)
            throw new Exception("扫描网络发生变化，拒绝跨 Network 套用优选结果");
        JSONArray originals=scan.optJSONArray("winners");
        if(originals==null)return 0;
        JSONArray saved=new JSONArray();
        LinkedHashSet<String> seen=new LinkedHashSet<>();
        for(int i=0;i<originals.length()&&saved.length()<16;i++){
            JSONObject obj=originals.optJSONObject(i);
            if(obj==null)continue;
            String ip=obj.optString("ip","");
            if(!validIp(ip)||!seen.add(ip))continue;
            double speed=obj.optDouble("speed_kib_s",0.0);
            if(!Double.isFinite(speed)||speed<0)speed=0;
            saved.put(new JSONObject()
                .put("ip",ip)
                .put("latency_ms",Math.max(1L,obj.optLong("latency_ms",999999L)))
                .put("speed_kib_s",speed)
                .put("benchmark_bytes",Math.max(0L,obj.optLong("benchmark_bytes",0L))));
        }
        if(saved.length()==0)return 0;
        JSONObject record=new JSONObject()
            .put("saved_at",System.currentTimeMillis())
            .put("network_handle",networkHandle)
            .put("winners",saved);
        SharedPreferences.Editor edit=storage.edit().putString(key(networkHandle),record.toString());
        String pinned=storage.getString(preferenceKey(networkHandle),null);
        if(pinned!=null&&!seen.contains(pinned))edit.remove(preferenceKey(networkHandle));
        if(!edit.commit())throw new IllegalStateException("优选 IP 持久写入失败");
        return saved.length();
    }
    private List<Candidate> sorted(long handle,boolean speedMode) {
        List<Candidate> found=candidates(handle,true);
        final int rank=speedMode?1:userPrefs.rankMode();
        if(rank==1){
            found.sort(Comparator.comparingDouble((Candidate x)->x.speed)
                .reversed().thenComparingLong(x->x.latency));
        }else if(rank==0){
            found.sort(Comparator.comparingLong((Candidate x)->x.latency)
                .thenComparing(Comparator.comparingDouble((Candidate x)->x.speed).reversed()));
        }else{
            double fastest=1;
            for(Candidate x:found)fastest=Math.max(fastest,x.speed);
            final double best=fastest;
            final double weight=userPrefs.scoreSpeedWeight()/100.0;
            found.sort((a,b)->Double.compare(
                heuristicScore(b,best,weight),
                heuristicScore(a,best,weight)));
        }
        return found;
    }
    /**
     * Weighted, bounded heuristic with measurement confidence: an untested
     * node cannot impersonate a fast node based solely on TLS RTT.
     * This is a ranking heuristic, not a proven predictor of large-file speed.
     */
    static double heuristicScore(Candidate item,double maxSpeed,double speedWeight){
        double latency=1.0/(1.0+item.latency/350.0);
        double speed=Math.log1p(item.speed)/Math.log1p(Math.max(1.0,maxSpeed));
        double confidence=Math.min(1.0,item.benchmarkBytes/262144.0);
        return (1.0-speedWeight)*latency+
            speedWeight*speed*(0.4+0.6*confidence);
    }
    public synchronized String[] selectionOptions(long handle,boolean speedMode) {
        List<Candidate> found=sorted(handle,speedMode);
        List<String> labels=new ArrayList<>();
        labels.add("自动优选（按当前排序与池多样性）");
        for(Candidate c:found)labels.add(c.ip+" · "+c.latency+" ms · "+
            (c.benchmarkBytes>0?String.format(Locale.ROOT,"%.0f KiB/s",c.speed):
                "未测速"));
        return labels.toArray(new String[0]);
    }
    public synchronized int preferredPosition(long handle,boolean speedMode) {
        String preferred=storage.getString(preferenceKey(handle),null);
        if(preferred==null)return 0;
        List<Candidate> found=sorted(handle,speedMode);
        for(int i=0;i<found.size();i++)if(preferred.equals(found.get(i).ip))return i+1;
        return 0;
    }
    public synchronized void manuallySelect(long handle,String ip,boolean speedMode) {
        if(ip==null||ip.isEmpty()){
            storage.edit().remove(preferenceKey(handle)).apply();
            return;
        }
        for(Candidate c:sorted(handle,speedMode)){
            if(ip.equals(c.ip)){
                storage.edit().putString(preferenceKey(handle),ip).apply();
                return;
            }
        }
    }
    /** Persisted verification must still be fresh on the selected Network. */
    public synchronized String[] verifiedSeedIps(long handle) {
        List<Candidate> list=sorted(handle,false);
        List<String> out=new ArrayList<>();
        for(Candidate c:list){
            if(out.size()==8)break;
            out.add(c.ip);
        }
        return out.toArray(new String[0]);
    }
    /** Never substitute static "verified" IPs when no valid scan exists. */
    public synchronized String[] addresses(long handle,boolean speedMode) {
        int limit=userPrefs.fallbackCount();
        List<Candidate> ranked=sorted(handle,speedMode);
        List<String> output=new ArrayList<>();
        Set<String> picked=new HashSet<>();
        Set<String> subnets=new HashSet<>();
        String pinned=storage.getString(preferenceKey(handle),null);
        for(Candidate c:ranked)if(c.ip.equals(pinned)){
            output.add(c.ip);picked.add(c.ip);subnets.add(c.subnet());break;
        }
        int diversity=userPrefs.subnetDiversity();
        while(output.size()<limit&&picked.size()<ranked.size()){
            Candidate best=null;
            double bestScore=Double.NEGATIVE_INFINITY;
            for(int i=0;i<ranked.size();i++){
                Candidate candidate=ranked.get(i);
                if(picked.contains(candidate.ip))continue;
                // Diversity (0..100) adds up to ~6 positions of preference
                // to another /24, but does not discard valid fallback peers.
                double value=(ranked.size()-i)*100.0+
                    (subnets.contains(candidate.subnet())?0:diversity*6.0);
                if(value>bestScore){bestScore=value;best=candidate;}
            }
            if(best==null)break;
            output.add(best.ip);picked.add(best.ip);subnets.add(best.subnet());
        }
        return output.toArray(new String[0]);
    }
    public synchronized boolean clear(long handle) {
        return storage.edit().remove(key(handle)).remove(preferenceKey(handle)).commit();
    }
    public synchronized String summary(long handle,boolean speedMode) {
        JSONObject saved=load(handle,false);
        if(saved==null){
            return "当前网络没有已验证的 IP。请先扫描；不会使用内置 IP 冒充验证结果。"+
                "扫描历史在本机保存，但其他网络的记录不会直接用于当前连接。";
        }
        boolean fresh=load(handle,true)!=null;
        long age=Math.max(0,System.currentTimeMillis()-saved.optLong("saved_at",0));
        List<Candidate> shown=fresh?sorted(handle,speedMode):candidates(handle,false);
        StringBuilder out=new StringBuilder();
        out.append(fresh?"可用验证结果":"历史验证结果已过期（禁止用于下载）")
            .append(" · ").append(shown.size()).append(" 个 IP")
            .append(" · ").append(Math.max(0,age/60000L)).append(" 分钟前\n");
        if(!fresh)out.append("需要在当前网络重新扫描才能使用。\n");
        int index=0;
        for(Candidate c:shown){
            if(++index>12)break;
            out.append(index).append(". ").append(c.ip)
                .append(" · ").append(c.latency).append(" ms · ")
                .append(c.benchmarkBytes>0?
                    String.format(Locale.ROOT,"%.0f KiB/s（实测 %d KiB）",
                        c.speed,c.benchmarkBytes/1024):
                    "未测速").append("\n");
        }
        return out.toString();
    }
}
