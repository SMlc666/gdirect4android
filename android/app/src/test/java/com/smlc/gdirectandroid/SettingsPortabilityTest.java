package com.smlc.gdirectandroid;

import android.content.Context;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public final class SettingsPortabilityTest {
    private Context context;
    private UserPrefs prefs;

    @Before public void setUp() {
        context=RuntimeEnvironment.getApplication();
        context.getSharedPreferences("gdirect_user_preferences_v1",
            Context.MODE_PRIVATE).edit().clear().commit();
        prefs=new UserPrefs(context);
        prefs.theme("light");
        prefs.scanSize(128);
        prefs.networkHandle(123456L);
    }

    @Test public void invalidImportDoesNotApplyPartialSettings() throws Exception {
        JSONObject backup=SettingsPortability.exportToJson(context);
        backup.getJSONObject("settings").put("theme","dark").put("scan_size",999);
        try {
            SettingsPortability.importFromJson(context,backup.toString());
            fail("Out-of-range settings must be rejected");
        } catch(IllegalArgumentException expected) {}
        assertEquals("light",prefs.theme());
        assertEquals(128,prefs.scanSize());
    }

    @Test public void oversizedJsonIntegerMustNotWrapToValidValue() throws Exception {
        JSONObject backup=SettingsPortability.exportToJson(context);
        backup.getJSONObject("settings").put("scan_size",4294967328L);
        try {
            SettingsPortability.importFromJson(context,backup.toString());
            fail("64-bit values must not be narrowed before validation");
        } catch(IllegalArgumentException expected) {}
        assertEquals(128,prefs.scanSize());
    }

    @Test public void multiIpDefaultsOffAndLegacyBackupStillImports() throws Exception {
        assertFalse(prefs.multiIp());
        JSONObject backup=SettingsPortability.exportToJson(context);
        backup.getJSONObject("settings").remove("multi_ip");
        prefs.multiIp(true);
        SettingsPortability.importFromJson(context,backup.toString());
        assertFalse(new UserPrefs(context).multiIp());
    }

    @Test public void multiIpPreferenceRoundTripsInExport() throws Exception {
        prefs.multiIp(true);
        JSONObject backup=SettingsPortability.exportToJson(context);
        assertTrue(backup.getJSONObject("settings").getBoolean("multi_ip"));
        prefs.multiIp(false);
        SettingsPortability.importFromJson(context,backup.toString());
        assertTrue(new UserPrefs(context).multiIp());
    }

    @Test public void exp3CustomScanPreferencesRoundTrip() throws Exception {
        prefs.scanPreset(3);
        prefs.benchmarkCount(28);
        prefs.scoreSpeedWeight(85);
        prefs.subnetDiversity(100);
        prefs.rankMode(2);
        JSONObject backup=SettingsPortability.exportToJson(context);
        prefs.scanPreset(0);
        prefs.benchmarkCount(4);
        prefs.scoreSpeedWeight(0);
        SettingsPortability.importFromJson(context,backup.toString());
        UserPrefs reloaded=new UserPrefs(context);
        assertEquals(3,reloaded.scanPreset());
        assertEquals(28,reloaded.benchmarkCount());
        assertEquals(85,reloaded.scoreSpeedWeight());
        assertEquals(100,reloaded.subnetDiversity());
        assertEquals(2,reloaded.rankMode());
    }
    @Test public void legacySchemaOneSettingsStayImportable() throws Exception {
        JSONObject backup=SettingsPortability.exportToJson(context);
        backup.put("schema",1);
        backup.getJSONObject("settings").remove("scan_preset")
            .remove("benchmark_count").remove("speed_weight")
            .remove("subnet_diversity");
        SettingsPortability.importFromJson(context,backup.toString());
        assertEquals(1,new UserPrefs(context).scanPreset());
        assertEquals(12,new UserPrefs(context).benchmarkCount());
    }
    @Test public void invalidExp3SettingsAreRejectedAtomically() throws Exception {
        JSONObject backup=SettingsPortability.exportToJson(context);
        backup.getJSONObject("settings").put("theme","dark")
            .put("speed_weight",101);
        try {
            SettingsPortability.importFromJson(context,backup.toString());
            fail("Speed weight 101 must be rejected");
        }catch(IllegalArgumentException expected){}
        assertEquals("light",new UserPrefs(context).theme());
    }

    @Test public void importedSettingsPersistWithoutExportingNetworkHandle() throws Exception {
        JSONObject backup=SettingsPortability.exportToJson(context);
        backup.getJSONObject("settings").put("theme","dark")
            .put("scan_size",512).put("connections",16);
        SettingsPortability.importFromJson(context,backup.toString());
        UserPrefs reopened=new UserPrefs(context);
        assertEquals("dark",reopened.theme());
        assertEquals(512,reopened.scanSize());
        assertEquals(16,reopened.connections());
        assertEquals(123456L,reopened.networkHandle());
        assertFalse(SettingsPortability.exportToJson(context).toString()
            .contains("123456"));
        assertFalse(reopened.multiIp());
    }
}
