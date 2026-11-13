package com.smlc.gdirectandroid;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public final class EndpointPoolTest {
    private Context context;
    private EndpointPool pool;
    private UserPrefs prefs;

    @Before public void setUp() {
        context=RuntimeEnvironment.getApplication();
        context.getSharedPreferences(EndpointPool.STORAGE_FILE,Context.MODE_PRIVATE)
            .edit().clear().commit();
        context.getSharedPreferences("gdirect_user_preferences_v1",Context.MODE_PRIVATE)
            .edit().clear().commit();
        pool=new EndpointPool(context);
        prefs=new UserPrefs(context);
    }

    private JSONObject scan(long handle)throws Exception {
        return new JSONObject().put("network_handle",handle)
            .put("success",4).put("winners",new JSONArray()
                .put(new JSONObject().put("ip","142.250.27.100")
                    .put("latency_ms",50).put("speed_kib_s",2048)
                    .put("benchmark_bytes",262144))
                .put(new JSONObject().put("ip","142.250.27.113")
                    .put("latency_ms",40).put("speed_kib_s",400)
                    .put("benchmark_bytes",262144))
                .put(new JSONObject().put("ip","108.177.9.138")
                    .put("latency_ms",70).put("speed_kib_s",1800)
                    .put("benchmark_bytes",262144))
                .put(new JSONObject().put("ip","64.233.160.100")
                    .put("latency_ms",100).put("speed_kib_s",1700)
                    .put("benchmark_bytes",262144)));
    }

    @Test public void noBundledValidatedIpsBeforeRealScan() {
        assertEquals(0,pool.addresses(123,false).length);
        assertEquals(0,pool.verifiedSeedIps(123).length);
        assertEquals(1,pool.selectionOptions(123,false).length);
        assertTrue(pool.summary(123,false).contains("没有已验证"));
    }

    @Test public void persistsScanAndManualPinAcrossInstancesButNotOtherNetwork()
            throws Exception {
        assertEquals(4,pool.accept(123,scan(123)));
        pool.manuallySelect(123,"108.177.9.138",false);
        EndpointPool reopened=new EndpointPool(context);
        assertEquals("108.177.9.138",reopened.addresses(123,false)[0]);
        assertEquals(0,reopened.addresses(124,false).length);
        assertEquals(0,reopened.verifiedSeedIps(124).length);
        assertEquals(2,reopened.preferredPosition(123,false));
    }

    @Test public void expiredRecordIsVisibleAsHistoryButNotUsedForConnection()
            throws Exception {
        pool.accept(123,scan(123));
        String name="route-123";
        android.content.SharedPreferences store=
            context.getSharedPreferences(EndpointPool.STORAGE_FILE,Context.MODE_PRIVATE);
        JSONObject old=new JSONObject(store.getString(name,"{}"));
        old.put("saved_at",System.currentTimeMillis()-200L*60_000L);
        assertTrue(store.edit().putString(name,old.toString()).commit());
        assertEquals(0,new EndpointPool(context).addresses(123,false).length);
        assertTrue(new EndpointPool(context).summary(123,false).contains("已过期"));
        assertTrue(store.contains(name));
    }

    @Test public void cancelledScanNeverErasesPriorValidatedEndpoints() throws Exception {
        pool.accept(123,scan(123));
        JSONObject cancelled=scan(123).put("cancelled",true);
        assertEquals(0,pool.accept(123,cancelled));
        assertEquals(4,new EndpointPool(context).addresses(123,false).length);
    }

    @Test public void ranksWithMeasurementConfidenceAndSubnetDiversity() throws Exception {
        pool.accept(123,scan(123));
        prefs.rankMode(1);
        assertEquals("142.250.27.100",pool.addresses(123,true)[0]);
        prefs.rankMode(2);
        prefs.scoreSpeedWeight(95);
        assertEquals("142.250.27.100",pool.addresses(123,false)[0]);
        prefs.subnetDiversity(100);
        String[] chosen=pool.addresses(123,false);
        assertEquals(4,chosen.length);
        assertFalse(chosen[0].equals(chosen[1]));
        EndpointPool.Candidate measured=new EndpointPool.Candidate(
            "142.250.27.100",60,2000,262144);
        EndpointPool.Candidate untested=new EndpointPool.Candidate(
            "142.250.27.113",60,0,0);
        assertTrue(EndpointPool.heuristicScore(measured,2000,.8)>
            EndpointPool.heuristicScore(untested,2000,.8));
    }

    @Test public void clearHistoryDoesNotInventAnyFallbackIps() throws Exception {
        pool.accept(123,scan(123));
        assertTrue(pool.clear(123));
        assertEquals(0,new EndpointPool(context).addresses(123,false).length);
    }
}
