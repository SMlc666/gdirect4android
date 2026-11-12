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
    }
}
