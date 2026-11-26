package com.bridge.filt3r;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

import com.bridge.filt3r.core.util.AppId;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads the installed apps for the screens that list them. Slow: call it off the main thread. */
public final class InstalledApps {

    private InstalledApps() {
    }

    /** One installed app. {@code group} holds the packages that share its user ID, itself included. */
    public static final class App {
        public final String pkg;
        public final String label;
        public final boolean system;
        public final Drawable icon;
        public final Set<String> group;

        App(String pkg, String label, boolean system, Drawable icon, Set<String> group) {
            this.pkg = pkg;
            this.label = label;
            this.system = system;
            this.icon = icon;
            this.group = group;
        }
    }

    /** Every installed package, launchable or not. This app is never listed. */
    public static List<App> load(Context context) {
        PackageManager pm = context.getPackageManager();
        String own = context.getPackageName();
        Map<String, App> byPackage = new HashMap<>();
        for (ApplicationInfo info : pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_ALL))) {
            add(pm, own, byPackage, info, String.valueOf(info.loadLabel(pm)), info.loadIcon(pm));
        }
        return new ArrayList<>(byPackage.values());
    }

    private static void add(PackageManager pm, String own, Map<String, App> byPackage, ApplicationInfo info,
            String label, Drawable icon) {
        if (info.packageName.equals(own) || byPackage.containsKey(info.packageName)) {
            return;
        }
        boolean system = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                && (info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
        Set<String> group = new LinkedHashSet<>();
        String[] sharing = pm.getPackagesForUid(info.uid);
        if (sharing != null) {
            group.addAll(Arrays.asList(sharing));
        }
        group.add(info.packageName);
        byPackage.put(info.packageName,
                new App(info.packageName, AppId.cleanLabel(label, info.packageName), system, icon, group));
    }
}
