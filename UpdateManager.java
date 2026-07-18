package com.brouken.runner;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/**
 * Wakes sleeping apps in small batches (default 4) instead of all at once, so the
 * phone doesn't get slammed launching dozens of apps together. After a batch is woken,
 * it periodically re-checks each app's installed versionName against the latest known
 * version and only removes an app from the run once its installed version actually
 * matches - it does not assume "launched = updated".
 *
 * Important: there is no API for a third-party app to silently force-install an update
 * for another app. This wakes the apps (so Play Store notices they're outdated) and
 * opens Play Store's "Manage apps & device / updates" screen so the user can tap
 * "Update all" there. This class detects when that update has actually landed.
 */
public class UpdateManager {

    private static final int BATCH_SIZE = 4;
    private static final long STAGGER_MS = 1200;      // delay between launching apps within a batch
    private static final long VERIFY_INTERVAL_MS = 5000; // how often to re-check installed versions
    private static final int MAX_VERIFY_TRIES = 12;   // ~1 minute per batch before giving up and moving on

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

    /** Wake + verify all given apps, BATCH_SIZE at a time. */
    public void updateAll(List<SleepingApp> apps) {
        batches = chunk(apps, BATCH_SIZE);
        batchIndex = 0;
        runNextBatch();
    }

    /** Wake + verify a single app (same logic, batch of one). */
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
            listener.onAllDone();
            return;
        }
        List<SleepingApp> batch = batches.get(batchIndex);
        listener.onBatchStarted(batch);
        wakeAppsStaggered(batch, 0);
    }

    private void wakeAppsStaggered(List<SleepingApp> batch, int i) {
        if (i >= batch.size()) {
            openPlayStoreUpdates();
            handler.postDelayed(() -> verifyBatch(batch, 0), VERIFY_INTERVAL_MS);
            return;
        }
        launchApp(batch.get(i).packageName);
        handler.postDelayed(() -> wakeAppsStaggered(batch, i + 1), STAGGER_MS);
    }

    private void verifyBatch(List<SleepingApp> pending, int tryCount) {
        List<SleepingApp> stillPending = new ArrayList<>();
        for (SleepingApp app : pending) {
            String installed = getInstalledVersion(app.packageName);
            boolean hasLatest = app.latestVersion != null && !app.latestVersion.isEmpty();
            boolean updated = hasLatest && app.latestVersion.equals(installed);

            if (updated) {
                app.currentVersion = installed;
                listener.onAppUpdated(app);
            } else {
                stillPending.add(app);
            }
        }

        if (stillPending.isEmpty() || tryCount + 1 >= MAX_VERIFY_TRIES) {
            // batch done (fully updated, or we gave up waiting - apps left in list for next scan/run)
            batchIndex++;
            runNextBatch();
        } else {
            handler.postDelayed(() -> verifyBatch(stillPending, tryCount + 1), VERIFY_INTERVAL_MS);
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
        Intent intent = new Intent("com.google.android.finsky.VIEW_MY_DOWNLOADS");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (intent.resolveActivity(context.getPackageManager()) != null) {
            context.startActivity(intent);
        }
    }
}
