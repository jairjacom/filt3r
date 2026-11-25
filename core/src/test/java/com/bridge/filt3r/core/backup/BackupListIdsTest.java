package com.bridge.filt3r.core.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The ids of the shipped filter lists contain spaces, quotes, "&", "(" and "+"; a restore must accept them. */
class BackupListIdsTest {

    private static String backupWithLists(String idsJson) {
        return "{\"app\":\"filt3r\",\"format\":1,\"settings\":{\"enabled_lists\":" + idsJson + "}}";
    }

    @Test
    void realListIdsAreAccepted() {
        List<String> ids = List.of("1Hosts-light", "ad&malware", "Dan Pollock's Hosts", "HageziPro++",
                "URLhaus (malware)", "pgl.yoyo.org");
        BackupFormat.Backup b = new BackupFormat.Backup();
        b.settings.put("enabled_lists", ids);
        BackupFormat.Backup read = BackupFormat.parse(BackupFormat.write(b, "test"));
        assertEquals(ids, read.settings.get("enabled_lists"));
    }

    @Test
    void anIdWithTheSeparatorOrNoTextIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> BackupFormat.parse(backupWithLists("[\"a;b\"]")));
        assertThrows(IllegalArgumentException.class, () -> BackupFormat.parse(backupWithLists("[\"\"]")));
    }
}
