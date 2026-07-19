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
 * Wakes sleeping apps in small batches (default 4) instead of all at once, so the phone
 * isn't slammed launching many apps together. Each batch is launched back-to-back with a
 * short stagger, then Play Store's "Manage apps & device" updates screen is opened once
 * the batch is done - waking batches never waits on update verification, so later batches
 * aren't stalled behind an earlier one that hasn't updated yet. Once every batch has been
 * woken, a single verification pass periodically re-checks all of them together: an app is
 * only marked updated once its live installed version differs from the version recorded at
 * scan time (currentVersion). This is robust to the app going back to sleep mid-update -
 * only the version number moving matters, not the app's enabled/sleep state.
 *
 * Note: a third-party app cannot silently force-install another app's update. This wakes
 * the apps so Play Store sees them as active/outdated; the user taps "Update all" in Play
 * Store, and this class detects when each update actually lands.
 */
public class UpdateManager {

    private static final int BATCH_SIZE = 4;
    private static final long STAGGER_MS = 1200;
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

    private List<List<SleepingApp>> batches;
    private int batchIndex;

    public UpdateManager(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void updateAll(List<SleepingApp> apps) {
        batches = chunk(apps, BATCH_SIZE);
        batchIndex = 0;
        runNextBatch();
    }

    public void updateSingle(SleepingApp app) {
        batches = new ArrayList<>();
        List<SleepingApp> single = new ArrayList<>();
        single.add(app);
        batches.add(single);
        batchIndex = 0;
        runNextBatch();
    }

    private List<List<SleepingApp>> chunk(List<SleepingApp> apps, int size) {
        List<List<SleepingApp>> out = new ArrayList<>();
        for (int i = 0; i < apps.size(); i += size) {
            out.add(new ArrayList<>(apps.subList(i, Math.min(i + size, apps.size()))));
        }
        return out;
    }

    private void runNextBatch() {
        if (batches == null || batchIndex >= batches.size()) {
            beginVerification();
            return;
        }
        List<SleepingApp> batch = batches.get(batchIndex);
        listener.onBatchStarted(batch);
        wakeAppsStaggered(batch, 0);
    }

    private void wakeAppsStaggered(List<SleepingApp> batch, int i) {
        if (i >= batch.size()) {
            // Whole batch launched - settle on Play Store's updates screen once, then
            // move on to the next batch instead of waiting for these to finish updating.
            openPlayStoreUpdates();
            batchIndex++;
            handler.postDelayed(this::runNextBatch, STAGGER_MS);
            return;
        }
        launchApp(batch.get(i).packageName);
        handler.postDelayed(() -> wakeAppsStaggered(batch, i + 1), STAGGER_MS);
    }

    private void beginVerification() {
        List<SleepingApp> all = new ArrayList<>();
        for (List<SleepingApp> b : batches) all.addAll(b);
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
