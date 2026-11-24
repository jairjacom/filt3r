package com.bridge.filt3r.core.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Custom answers and wildcards in the user's own rules file. */
class UserOverridesTest {

    @TempDir
    File dir;

    private UserOverrides overrides() {
        return new UserOverrides(new File(dir, "additionalHosts.txt"));
    }

    private String fileText() throws IOException {
        return new String(Files.readAllBytes(new File(dir, "additionalHosts.txt").toPath()), StandardCharsets.UTF_8);
    }

    @Test
    void aCustomAnswerIsListedAndKeptApartFromRules() throws IOException {
        UserOverrides o = overrides();
        o.set("ads.example.com", false);
        o.setAnswer("Router.Home", "192.168.1.1");
        List<UserOverrides.Entry> all = o.list();
        assertEquals(2, all.size());
        assertFalse(all.get(0).isAnswer());
        assertTrue(all.get(1).isAnswer());
        assertEquals("router.home", all.get(1).host);
        assertEquals("192.168.1.1", all.get(1).ip);
        assertTrue(fileText().contains(">router.home 192.168.1.1"));
        assertNull(o.find("router.home"));
    }

    @Test
    void settingAnAnswerAgainReplacesIt() throws IOException {
        UserOverrides o = overrides();
        o.setAnswer("nas.home", "192.168.1.5");
        o.setAnswer("nas.home", "192.168.1.6");
        assertEquals(1, o.list().size());
        assertEquals("192.168.1.6", o.list().get(0).ip);
    }

    @Test
    void removingAnAnswerLeavesTheRuleForTheSameName() throws IOException {
        UserOverrides o = overrides();
        o.set("nas.home", false);
        o.setAnswer("nas.home", "192.168.1.5");
        o.removeAnswer("nas.home");
        assertEquals(1, o.list().size());
        assertFalse(o.list().get(0).isAnswer());
        o.setAnswer("nas.home", "192.168.1.5");
        o.remove("nas.home");
        assertEquals(1, o.list().size());
        assertTrue(o.list().get(0).isAnswer());
    }

    @Test
    void wildcardsWorkInRulesAndAnswers() throws IOException {
        UserOverrides o = overrides();
        o.set("*.tracker.example", false);
        o.setAnswer("*.home", "10.0.0.2");
        assertEquals(Boolean.FALSE, o.find("*.tracker.example"));
        assertEquals("*.home", o.list().get(1).host);
        o.remove("*.tracker.example");
        assertEquals(1, o.list().size());
    }

    @Test
    void aBareWildcardIsRefused() {
        UserOverrides o = overrides();
        assertThrows(IllegalArgumentException.class, () -> o.set("*", false));
        assertThrows(IllegalArgumentException.class, () -> o.set("*.*", false));
        assertThrows(IllegalArgumentException.class, () -> o.setAnswer("*", "1.2.3.4"));
    }

    @Test
    void onlyNumericAddressesAreAccepted() {
        UserOverrides o = overrides();
        assertThrows(IllegalArgumentException.class, () -> o.setAnswer("a.home", "example.com"));
        assertThrows(IllegalArgumentException.class, () -> o.setAnswer("a.home", "256.1.1.1"));
        assertThrows(IllegalArgumentException.class, () -> o.setAnswer("a.home", "1.2.3"));
        assertThrows(IllegalArgumentException.class, () -> o.setAnswer("a.home", "::zz"));
        assertThrows(IllegalArgumentException.class, () -> o.setAnswer("a.home", ""));
    }

    @Test
    void anIpv6AnswerIsAccepted() throws IOException {
        UserOverrides o = overrides();
        assertEquals("fd00::1", o.setAnswer("a.home", "FD00::1").ip);
    }

    @Test
    void commentsSurviveEdits() throws IOException {
        File f = new File(dir, "additionalHosts.txt");
        Files.write(f.toPath(), "# mine\n".getBytes(StandardCharsets.UTF_8));
        UserOverrides o = new UserOverrides(f);
        o.setAnswer("a.home", "10.0.0.1");
        o.removeAnswer("a.home");
        assertEquals("# mine\n", fileText());
    }
}
