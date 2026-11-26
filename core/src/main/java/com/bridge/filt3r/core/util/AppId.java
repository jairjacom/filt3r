package com.bridge.filt3r.core.util;

/**
 * Who owns a connection. The key is what saved data stores: the package name, which survives an
 * uninstall and reinstall (the uid usually does not). The label is for display only.
 */
public final class AppId {

    private final String key;
    private final String label;
    private final int uid;

    private AppId(String key, String label, int uid) {
        this.key = key;
        this.label = cleanLabel(label, key);
        this.uid = uid;
    }

    /** An app picks its own label, so it is untrusted: control and format characters become spaces, and it is cut to 64 characters. */
    private static String cleanLabel(String label, String key) {
        String clean = label == null ? "" : label.replaceAll("[\\p{Cc}\\p{Cf}]", " ").trim();
        if (clean.codePointCount(0, clean.length()) > MAX_LABEL_LENGTH) {
            clean = clean.substring(0, clean.offsetByCodePoints(0, MAX_LABEL_LENGTH)).trim();
        }
        return clean.isEmpty() ? key : clean;
    }

    private static final int MAX_LABEL_LENGTH = 64;

    /** First uid Android gives to an installed app (android.os.Process.FIRST_APPLICATION_UID); below it are system uids. */
    private static final int FIRST_APP_UID = 10000;

    /**
     * @param packages the packages Android reports for the uid; null or empty when it hides them
     * @param label the display name the caller chose
     * @param systemName the name for a uid below the app range, used only when no package is known
     */
    public static AppId of(String[] packages, int uid, String label, String systemName) {
        if (packages == null || packages.length == 0) {
            if (uid < FIRST_APP_UID && systemName != null) {
                return new AppId("system:" + systemName, label, uid);
            }
            return new AppId("uid:" + uid, label, uid);
        }
        return new AppId(packages[0], label, uid);
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    public int uid() {
        return uid;
    }
}
