package com.pratiks360.deepwake;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles SINGLE-app updates: wake the sleeping app, then immediately hand the foreground
 * to Play Store's updates screen. Both activity starts fire synchronously, back-to-back,
 * in the same call stack as the user's tap on Update - Android's background-activity-start
 * restrictions can silently drop an activity start that isn't a direct, immediate
 * continuation of a foreground app's own action (a foreground service alone isn't exempt),
 * which is why no Handler delay is allowed anywhere in the wake path.
 *
 * Batch updates live in AutoUpdateService instead - being a system-bound accessibility
 * service exempts it from those restrictions, which multi-batch orchestration needs.
 *
 * Verification then polls until the installed version actually moves: an app is only
 * marked updated once its live installed version differs from the version recorded at
 * scan time (currentVersion). This is robust to the app going back to sleep mid-update -
 * only the version number moving matters, not the app's enabled/sleep state.
 *
 * Note: a third-party app cannot silently force-install another app's update. This wakes
 * the app so Play Store sees it as active/outdated; the user taps Update in Play Store,
 * and this class detects when the update actually lands.
 */
public class UpdateManager {

    private static final long VERIFY_INTERVAL_MS = 5000;
    private static final int MAX_VERIFY_TRIES = 24; // ~2 min verification window

    public interface Listener {
        void onAppUpdated(SleepingApp app);
        void onBatchStarted(List<SleepingApp> batch);
        void onAllDone();
    }

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Listener listener;

    public UpdateManager(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void updateSingle(SleepingApp app) {
        List<SleepingApp> single = new ArrayList<>();
        single.add(app);
        listener.onBatchStarted(single);
        launchApp(app.packageName);
        openPlayStoreUpdates();
        handler.postDelayed(() -> verifyPending(single, 0), VERIFY_INTERVAL_MS);
    }

    private void verifyPending(List<SleepingApp> pending, int tryCount) {
        List<SleepingApp> stillPending = new ArrayList<>();
        for (SleepingApp app : pending) {
            String installed = getInstalledVersion(app.packageName);
            boolean updated;
            if (app.latestVersion != null && !app.latestVersion.isEmpty()
                    && !app.latestVersion.equals(PlayStoreVersionFetcher.NET_ERROR)
                    && !app.latestVersion.equals(PlayStoreVersionFetcher.NO_MATCH)) {
                // we know the target version -> updated when installed reaches it
                updated = app.latestVersion.equals(installed);
            } else {
                // no known target -> updated when installed simply changed from baseline
                updated = installed != null && !installed.equals(app.currentVersion);
            }

            if (updated) {
                app.currentVersion = installed;
                listener.onAppUpdated(app);
            } else {
                stillPending.add(app);
            }
        }

        if (stillPending.isEmpty() || tryCount + 1 >= MAX_VERIFY_TRIES) {
            listener.onAllDone();
        } else {
            handler.postDelayed(() -> verifyPending(stillPending, tryCount + 1), VERIFY_INTERVAL_MS);
        }
    }

    private String getInstalledVersion(String packageName) {
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(packageName, 0);
            return pi.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private void launchApp(String packageName) {
        PackageManager pm = context.getPackageManager();
        Intent intent = pm.getLaunchIntentForPackage(packageName);
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        }
    }

    private void openPlayStoreUpdates() {
        Intent deepLink = new Intent("com.google.android.finsky.VIEW_MY_DOWNLOADS");
        deepLink.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (tryStart(deepLink)) return;

        // The internal deep link isn't handled on this Play Store build/version - fall
        // back to just bringing Play Store itself to the foreground (its own launcher
        // intent is always resolvable) rather than silently no-op'ing and leaving
        // whichever app we just launched sitting on screen.
        PackageManager pm = context.getPackageManager();
        Intent launch = pm.getLaunchIntentForPackage("com.android.vending");
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            tryStart(launch);
        }
    }

    private boolean tryStart(Intent intent) {
        if (intent.resolveActivity(context.getPackageManager()) != null) {
            context.startActivity(intent);
            return true;
        }
        return false;
    }
}
