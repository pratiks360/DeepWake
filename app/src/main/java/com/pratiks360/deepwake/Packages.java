package com.pratiks360.deepwake;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Every PackageManager lookup that concerns a sleeping app goes through here.
 *
 * The reason this class exists at all: a deep-sleeping app is a DISABLED or
 * DISABLED_UNTIL_USED package, and PackageManager hides those from an ordinary query. Ask
 * with flags = 0 and a hibernated package is simply not found - which every caller then
 * reads as its own kind of "nothing to do": the scan doesn't list it, isAsleep() decides
 * it's already awake, and the installed-version check comes back null. Each of those is a
 * silent skip, and the app disappears from the tool for exactly the reason it should be
 * in it. Passing MATCH_SLEEPING is what keeps that from happening.
 */
public final class Packages {

    /**
     * Both flags are needed. MATCH_DISABLED_COMPONENTS covers a package disabled outright;
     * hibernation uses DISABLED_UNTIL_USED, which is a separate match flag, and that is the
     * state most "deep sleeping" apps are actually in.
     */
    public static final int MATCH_SLEEPING = PackageManager.MATCH_DISABLED_COMPONENTS
            | PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS;

    private Packages() {
    }

    /**
     * Every package with a launcher entry - i.e. an app the user can see and open.
     *
     * The scan uses this as its "is this a real app" test, replacing two filters that were
     * each dropping real apps. The first skipped anything with FLAG_SYSTEM; the second
     * required the installer to be com.android.vending. Both are wrong for a preloaded app:
     * an OEM preload keeps FLAG_SYSTEM and its original installer for life, even once Play
     * Store has updated it several times over. That is exactly how Meesho ships on a lot of
     * phones, so the scan never saw it. A restored-from-backup app fails the same test from
     * the other side - no installer recorded at all.
     *
     * A launcher entry is the honest version of what those two filters were reaching for:
     * keep user-facing apps, drop OS plumbing. It costs a Play Store lookup for the handful
     * of sleeping apps that turn out not to be on Play, and those resolve to a failure
     * marker and never reach the list.
     */
    public static Set<String> launchable(PackageManager pm) {
        Set<String> out = new HashSet<>();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(launcher, MATCH_SLEEPING)) {
            if (ri.activityInfo != null) out.add(ri.activityInfo.packageName);
        }
        return out;
    }

    /**
     * An intent that launches the app - the wake itself.
     *
     * getLaunchIntentForPackage resolves with flags = 0, so for a hibernated package it
     * returns null and the wake silently no-ops. The fallback resolves the same launcher
     * activity with the match flags on and addresses it explicitly by component.
     */
    public static Intent launchIntent(PackageManager pm, String packageName) {
        Intent intent = pm.getLaunchIntentForPackage(packageName);
        if (intent != null) return intent;

        Intent launcher = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(packageName);
        List<ResolveInfo> matches = pm.queryIntentActivities(launcher, MATCH_SLEEPING);
        if (matches.isEmpty()) return null;
        ResolveInfo ri = matches.get(0);
        return new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(ri.activityInfo.packageName, ri.activityInfo.name);
    }

    /** The installed versionName, or null if the package really isn't there. */
    public static String installedVersion(PackageManager pm, String packageName) {
        try {
            PackageInfo pi = pm.getPackageInfo(packageName, MATCH_SLEEPING);
            return pi.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /**
     * Whether the app is currently sleeping. A package that has fallen back into hibernation
     * reports enabled == false again - the same signal the scan detects it by. A package
     * that genuinely isn't installed counts as awake: there is nothing left to wake.
     */
    public static boolean isAsleep(PackageManager pm, String packageName) {
        try {
            return !pm.getApplicationInfo(packageName, MATCH_SLEEPING).enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
