package com.bridge.filt3r;

import com.bridge.filt3r.core.backup.BackupFormat;
import com.bridge.filt3r.core.filter.UserOverrides;
import com.bridge.filt3r.core.shadowsocks.ShadowsocksNode;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Reads settings, exempt apps and domain rules into a backup file, and applies a checked backup back. */
final class SettingsBackup {

    /** What an import changed, for the message shown afterwards. */
    static final class Result {
        int settings;
        int apps = -1; // -1: the file had no app list
        int rulesApplied;
        int rulesSkipped;
        int subscriptions;
        int nodes;
    }

    private SettingsBackup() {
    }

    /**
     * @param secrets true to include the subscription addresses and the pasted nodes; they hold secrets
     */
    static String export(Settings settings, UserOverrides overrides, File filesDir, boolean secrets,
                         String version) throws IOException {
        BackupFormat.Backup b = new BackupFormat.Backup();
        b.settings.putAll(settings.backupValues());
        b.exemptApps = new ArrayList<>(new java.util.TreeSet<>(settings.exemptApps()));
        for (UserOverrides.Entry e : overrides.list()) {
            b.rules.add(new BackupFormat.Rule(e.host, e.allow));
        }
        if (secrets) {
            for (Settings.Subscription s : settings.subscriptions()) {
                b.subscriptions.add(new BackupFormat.Subscription(s.slot, s.url, s.intervalHours, s.enabled));
            }
            for (ShadowsocksNode n : ManualNodes.read(filesDir)) {
                b.manualNodes.add(n.toUri());
            }
        }
        return BackupFormat.write(b, version);
    }

    /** Applies a backup that {@link BackupFormat#parse} accepted. Call only after the user confirmed. */
    static Result apply(BackupFormat.Backup b, Settings settings, UserOverrides overrides, File filesDir)
            throws IOException {
        Result r = new Result();
        settings.restoreValues(b.settings);
        r.settings = b.settings.size();
        if (b.exemptApps != null) {
            settings.setExemptApps(new HashSet<>(b.exemptApps));
            r.apps = b.exemptApps.size();
        }
        for (BackupFormat.Rule rule : b.rules) {
            try {
                overrides.set(rule.host, rule.allow);
                r.rulesApplied++;
            } catch (IllegalArgumentException e) {
                r.rulesSkipped++; // not a domain name UserOverrides accepts
            }
        }
        for (BackupFormat.Subscription s : b.subscriptions) {
            settings.setSubscription(s.slot, s.url, s.hours);
            settings.setSubscriptionEnabled(s.slot, s.on);
            r.subscriptions++;
        }
        for (String uri : b.manualNodes) {
            if (ManualNodes.add(filesDir, ShadowsocksNode.parse(uri))) {
                r.nodes++;
            }
        }
        return r;
    }
}
