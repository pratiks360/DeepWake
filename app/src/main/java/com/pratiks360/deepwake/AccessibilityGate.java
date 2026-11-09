package com.updatehelper.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Switches this app's accessibility service on for a batch run and off again when it ends.
 *
 * Why: an enabled accessibility service is visible to every app on the device - through
 * AccessibilityManager.getEnabledAccessibilityServiceList() and the
 * enabled_accessibility_services setting, both readable without any permission. That is what
 * makes banking apps put up an "accessibility service detected" warning. It cannot be hidden
 * while it is on; the only thing that helps is that it should not be on when you are not
 * running an update.
 *
 * So the service is off by default and is only enabled for the minutes a run actually takes.
 * Enabling it that way needs WRITE_SECURE_SETTINGS, which no runtime dialog can grant - it
 * has to come from a computer over adb, or from Shizuku:
 *
 *     adb shell pm grant com.updatehelper.app android.permission.WRITE_SECURE_SETTINGS
 *
 * Without that grant nothing breaks: the app falls back to the old behaviour, where the user
 * turns the service on once in Settings and it stays on. See isGranted().
 */
final class AccessibilityGate {

    private AccessibilityGate() {
    }

    /** Whether the one-time adb/Shizuku grant is in place. */
    static boolean isGranted(Context context) {
        return context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Whether our service is currently listed as enabled in the system settings. */
    static boolean isServiceEnabled(Context context) {
        String enabled = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        for (String entry : enabled.split(":")) {
            if (isOurs(context, entry)) return true;
        }
        return false;
    }

    /**
     * Adds or removes our service from enabled_accessibility_services, leaving every other
     * enabled service untouched - this must never switch off someone's screen reader.
     *
     * @return true when the setting now says what was asked for. False when the permission
     *         is missing (or the ROM refused the write), in which case the caller has to fall
     *         back to the manual enable-in-Settings flow.
     */
    static boolean setServiceEnabled(Context context, boolean enable) {
        if (!isGranted(context)) return false;
        try {
            String raw = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            List<String> others = new ArrayList<>();
            boolean present = false;
            if (raw != null) {
                for (String entry : raw.split(":")) {
                    if (entry.isEmpty()) continue;
                    if (isOurs(context, entry)) {
                        present = true;
                    } else {
                        others.add(entry);
                    }
                }
            }
            if (present == enable) return true; // already in the wanted state

            if (enable) others.add(component(context));
            Settings.Secure.putString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    TextUtils.join(":", others));

            if (enable) {
                // A service only gets bound while accessibility itself is on.
                Settings.Secure.putInt(context.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            } else if (others.isEmpty()) {
                // Nothing left enabled - turn accessibility off too, so the system doesn't
                // sit there with it flagged on and no services.
                Settings.Secure.putInt(context.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_ENABLED, 0);
            }
            return true;
        } catch (Exception e) {
            // SecurityException if the grant was revoked (app reinstalled, permission
            // dropped), and any other refusal some OEM ROMs make. Callers fall back.
            return false;
        }
    }

    /**
     * The system stores components as "pkg/full.class" when Settings writes them and as
     * "pkg/.Class" when they come from elsewhere, so both forms have to match.
     */
    private static boolean isOurs(Context context, String entry) {
        int slash = entry.indexOf('/');
        if (slash <= 0) return false;
        if (!entry.substring(0, slash).equals(context.getPackageName())) return false;
        String cls = entry.substring(slash + 1);
        return cls.equals(AutoUpdateService.class.getName())
                || cls.equals("." + AutoUpdateService.class.getSimpleName());
    }

    private static String component(Context context) {
        return new ComponentName(context, AutoUpdateService.class).flattenToString();
    }
}
