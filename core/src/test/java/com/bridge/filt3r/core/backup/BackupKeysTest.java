package com.bridge.filt3r.core.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** A backed-up key that BackupFormat does not know would be dropped silently on restore. */
class BackupKeysTest {

    private static final String STAMP =
            "sdns://AQcAAAAAAAAADjE5OC4xNDAuMTQxLjQ2IFqbafOxgXuKwOgYxQ6XUqHWkMUt_5LI2nDkdVFU5hm7GTIuZG5zY3J5cHQtY2VydC5kbnNjcnkucHQ";

    /** One valid value per backed-up key. Adding a key to BackupKeys means adding a sample here. */
    private static Map<String, Object> samples() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("failure_mode", "FAIL_CLOSED");
        m.put("query_log", true);
        m.put("resolver_name", "Test resolver");
        m.put("resolver_stamp", STAMP);
        m.put("node_sort_speed", true);
        m.put("filter_url", "https://example.org/list.txt");
        m.put("enabled_lists", List.of("list-a", "list-b"));
        m.put("ip_block", true);
        m.put("test_only_cheap", false);
        m.put("list_refresh_days", 7);
        m.put("toolbar_pinned", true);
        m.put("toolbar_style", "docked");
        m.put("theme_mode", 2);
        m.put("pure_black", true);
        m.put("color_scheme", "vibrant");
        m.put("seed_color", -65536);
        m.put("effects_level", 3);
        m.put("transition_style", 1);
        m.put("switch_faster", true);
        m.put("bypass_lan", false);
        return m;
    }

    @Test
    void theSampleTableCoversExactlyTheBackedUpKeys() {
        assertEquals(BackupKeys.KEYS, samples().keySet());
    }

    @Test
    void everyBackedUpKeySurvivesAWriteAndParse() {
        BackupFormat.Backup b = new BackupFormat.Backup();
        b.settings.putAll(samples());
        BackupFormat.Backup back = BackupFormat.parse(BackupFormat.write(b, "0.0.0"));
        for (Map.Entry<String, Object> e : samples().entrySet()) {
            assertTrue(back.settings.containsKey(e.getKey()), "dropped on restore: " + e.getKey());
            assertEquals(e.getValue(), back.settings.get(e.getKey()), e.getKey());
        }
    }

    @Test
    void effectSwitchesAreCarriedAndOtherKeysAreNot() {
        assertTrue(BackupKeys.carries("fx_blur"));
        assertTrue(BackupKeys.carries("failure_mode"));
        assertEquals(false, BackupKeys.carries("pinned_node"));
        assertEquals(false, BackupKeys.carries("hidden_nodes"));
        assertEquals(false, BackupKeys.carries("sub_url_0"));
    }
}
