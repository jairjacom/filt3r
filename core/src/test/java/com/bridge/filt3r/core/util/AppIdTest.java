package com.bridge.filt3r.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** What identifies an app in saved data: the package name, with fallbacks when Android hides it. */
class AppIdTest {

    @Test
    void anInstalledAppIsKeyedByItsPackageNameNotItsUid() {
        AppId before = AppId.of(new String[] {"org.example.chat"}, 10123, "Chat", null);
        AppId afterReinstall = AppId.of(new String[] {"org.example.chat"}, 10456, "Chat", null);
        assertEquals("org.example.chat", before.key());
        assertEquals(before.key(), afterReinstall.key());
    }

    @Test
    void anAppAndroidHidesIsKeyedByItsUid() {
        assertEquals("uid:10234", AppId.of(null, 10234, "App 10234", null).key());
        assertEquals("uid:10235", AppId.of(new String[0], 10235, "App 10235", null).key());
    }

    @Test
    void aSystemUidWithNoPackageIsKeyedByItsSystemName() {
        assertEquals("system:shell", AppId.of(null, 2000, "System (shell)", "shell").key());
        assertEquals("system:root", AppId.of(null, 0, "System (root)", "root").key());
    }

    @Test
    void anAppsOwnLabelCannotBreakALogLine() {
        // line break, tab, right-to-left override: an app picks its label, so it is untrusted
        AppId id = AppId.of(new String[] {"org.example.x"}, 10001, "Evil\nApp\t\u202Eexe", null);
        assertEquals("Evil App  exe", id.label());
    }

    @Test
    void aLongLabelIsCutAtSixtyFourCharactersNotInsideASurrogatePair() {
        String emoji = new String(Character.toChars(0x1F600));
        AppId id = AppId.of(new String[] {"org.example.x"}, 10001, emoji.repeat(70), null);
        assertEquals(emoji.repeat(64), id.label());
    }

    @Test
    void aLabelWithNothingPrintableFallsBackToTheKey() {
        assertEquals("org.example.x", AppId.of(new String[] {"org.example.x"}, 10001, "\n\t", null).label());
        assertEquals("uid:10234", AppId.of(null, 10234, null, null).label());
    }
}
