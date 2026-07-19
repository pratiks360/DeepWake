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
 * Wakes sleeping apps in small batches (default 4), then opens Play Store's "Manage apps &
 * device" updates screen. Every activity start here (each woken app, then Play Store) is
 * fired synchronously, back-to-back, in the same call stack as the user's tap on Update /
 * Update All - Android's background-activity-start restrictions can silently drop an
 * activity start that isn't a direct, immediate continuation of a foreground app's own
 * action, and merely running a foreground service isn't enough on its own to be exempt.
 * A `Handler.postDelayed` in between (as this used to do, to stagger the launches and let
 * each app "flash" on screen before reclaiming Play Store) falls outside that window once
 * this app itself is no longer visible, which left the most recently launched app stuck on
 * screen with the Play Store switch silently never happening.
 *
 * Once every batch has been woken, a single verification pass periodically re-checks all of
 * them together (this doesn't start any activities, so it's free to use delayed polling): an
 * app is only marked updated once its live installed version differs from the version
 * recorded at scan time (currentVersion). This is robust to the app going back to sleep
 * mid-update - only the version number moving matters, not the app's enabled/sleep state.
 *
 * Note: a third-party app cannot silently force-install another app's update. This wakes
 * the apps so Play Store sees them as active/outdated; the user taps "Update all" in Play
 * Store, and this class detects when each update actually lands.
 */
public class UpdateManager {

    private static final int BATCH_SIZE = 4;
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

    public void updateAll(List<SleepingApp> apps) {
        wakeAllBatches(chunk(apps, BATCH_SIZE));
    }

    public void updateSingle(SleepingApp app) {
        List<SleepingApp> single = new ArrayList<>();
        single.add(app);
        List<List<SleepingApp>> batches = new ArrayList<>();
        batches.add(single);
        wakeAllBatches(batches);
    }

    private List<List<SleepingApp>> chunk(List<SleepingApp> apps, int size) {
        List<List<SleepingApp>> out = new ArrayList<>();
        for (int i = 0; i < apps.size(); i += size) {
            out.add(new ArrayList<>(apps.subList(i, Math.min(i + size, apps.size()))));
        }
        return out;
    }

    private void wakeAllBatches(List<List<SleepingApp>> batches) {
        List<SleepingApp> all = new ArrayList<>();
        for (List<SleepingApp> batch : batches) {
            listener.onBatchStarted(batch);
            for (SleepingApp app : batch) {
                launchApp(app.packageName);
                all.add(app);
            }
            // Reclaim the foreground for Play Store right after this batch's launches,
            // still in the same synchronous call chain - see class doc for why this must
            // not go through a delayed callback.
            openPlayStoreUpdates();
        }
        beginVerification(all);
    }

    private void beginVerification(List<SleepingApp> all) {
        if (all.isEmpty()) {
            listener.onAllDone();
            return;
        }
        handler.postDelayed(() -> verifyPending(all, 0), VERIFY_INTERVAL_MS);
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
