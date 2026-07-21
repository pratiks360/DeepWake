package com.pratiks360.deepwake;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs scanning and waking/updating as a foreground service instead of on the Activity,
 * so both survive the app being minimised - a plain background process (no foreground
 * service) can be killed by Android at any point, which was silently aborting scans and
 * losing unsaved progress whenever the user switched away mid-scan.
 */
public class ScanService extends Service implements UpdateManager.Listener {

    private static final String CHANNEL_ID = "deepwake_activity";
    private static final int NOTIFICATION_ID = 1;
    // Play Store fetches are network-bound (up to a 12s timeout each) and independent, so
    // they run on a small pool in parallel. Kept modest to avoid hammering Play Store.
    private static final int FETCH_THREADS = 6;

    public interface Listener {
        void onScanStarted();
        void onRowUpdated(SleepingApp app);
        void onScanFinished();
        void onAppUpdated(SleepingApp app);
        void onBatchStarted(List<SleepingApp> batch);
        void onUpdateAllFinished();
    }

    public class LocalBinder extends Binder {
        ScanService getService() {
            return ScanService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService controlExecutor; // orchestrates a scan (single thread)
    private ExecutorService fetchPool;       // parallel Play Store version fetches
    private UpdateManager updateManager;
    private Listener listener;
    private volatile boolean scanning;

    @Override
    public void onCreate() {
        super.onCreate();
        controlExecutor = Executors.newSingleThreadExecutor();
        fetchPool = Executors.newFixedThreadPool(FETCH_THREADS);
        updateManager = new UpdateManager(this, this);
        createChannel();
    }

    @Override
    public void onDestroy() {
        scanning = false;
        if (controlExecutor != null) controlExecutor.shutdownNow();
        if (fetchPool != null) fetchPool.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Sticky isn't needed - each action is kicked off explicitly by the caller and
        // this service stops itself once that action completes.
        return START_NOT_STICKY;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isScanning() {
        return scanning;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "DeepWake activity", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(channel);
        }
    }

    private void updateNotification(String text) {
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("DeepWake")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat_deepwake)
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION_ID, n);
    }

    public void startScan() {
        if (scanning) return;
        scanning = true;
        updateNotification("Scanning for sleeping apps...");
        if (listener != null) listener.onScanStarted();

        controlExecutor.execute(() -> {
            PackageManager pm = getPackageManager();

            // Phase 1 (fast, on this control thread): enumerate the sleeping apps and show
            // every row up front as "checking...". No network here, so it returns quickly.
            // preserve any previously tracked entries not re-encountered this scan
            Map<String, SleepingApp> merged = new LinkedHashMap<>();
            for (SleepingApp a : AppListStorage.load(this)) merged.put(a.packageName, a);

            List<SleepingApp> toFetch = new ArrayList<>();
            for (ApplicationInfo info : pm.getInstalledApplications(0)) {
                if (!scanning) break;
                if ((info.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                if (info.enabled) continue; // only deep-sleeping (disabled) apps
                String installer;
                try {
                    installer = pm.getInstallerPackageName(info.packageName);
                } catch (Exception e) {
                    installer = null;
                }
                if (!"com.android.vending".equals(installer)) continue;

                String current = getInstalledVersion(pm, info.packageName);
                String appName = String.valueOf(pm.getApplicationLabel(info));

                // Seed with the previously-known latest version if we have one, instead of
                // wiping it to "checking...". Otherwise an interrupted rescan (this device
                // kills background work aggressively) would persist every app as
                // "checking..." - which filters out as not-outdated and leaves the list
                // empty on the next open. Keeping the prior value means the list survives.
                SleepingApp prior = merged.get(info.packageName);
                String prevLatest = prior != null ? prior.latestVersion : null;
                String seedLatest = (prevLatest != null && !prevLatest.isEmpty()
                        && !prevLatest.equals("checking...")) ? prevLatest : "checking...";

                SleepingApp app = new SleepingApp(info.packageName, appName, current, seedLatest);
                merged.put(info.packageName, app);
                toFetch.add(app);
                notifyRow(app, "Found " + appName + "...");
            }

            // Fixed snapshot of everything we persist. We only mutate each app's
            // latestVersion field from here on (never add/remove), so concurrent fetch
            // threads iterating this list to save is safe.
            final List<SleepingApp> snapshot = new ArrayList<>(merged.values());
            AppListStorage.save(this, snapshot);

            // Phase 2 (parallel, on the fetch pool): each app's Play Store lookup is an
            // independent network call, so they run concurrently instead of one-at-a-time.
            final int total = toFetch.size();
            final AtomicInteger done = new AtomicInteger(0);
            List<Future<?>> futures = new ArrayList<>();
            for (SleepingApp app : toFetch) {
                futures.add(fetchPool.submit(() -> {
                    if (!scanning) return;
                    app.latestVersion =
                            PlayStoreVersionFetcher.fetchLatestVersion(app.packageName, app.currentVersion);

                    // AppListStorage.save is static-synchronized, so concurrent saves from
                    // different fetch threads are serialized. Save as each result lands so a
                    // mid-scan process kill loses at most the fetches still in flight.
                    AppListStorage.save(this, snapshot);

                    int c = done.incrementAndGet();
                    notifyRow(app, "Checked " + c + "/" + total + "...");
                }));
            }

            // Block the control thread until every fetch finishes (or the scan is stopped).
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception ignored) {
                    // interrupted / cancelled on shutdown, or a fetch threw - either way we
                    // just move on; that app keeps its "checking..." marker.
                }
            }

            scanning = false;
            mainHandler.post(() -> {
                if (listener != null) listener.onScanFinished();
            });
            stopForeground(true);
            stopSelf();
        });
    }

    public void startUpdateSingle(SleepingApp app) {
        updateNotification("Waking " + app.appName + "...");
        updateManager.updateSingle(app);
    }

    @Override
    public void onAppUpdated(SleepingApp app) {
        List<SleepingApp> all = AppListStorage.load(this);
        all.removeIf(a -> a.packageName.equals(app.packageName));
        AppListStorage.save(this, all);
        mainHandler.post(() -> {
            if (listener != null) listener.onAppUpdated(app);
        });
    }

    @Override
    public void onBatchStarted(List<SleepingApp> batch) {
        updateNotification("Waking " + batch.size() + " app(s)...");
        mainHandler.post(() -> {
            if (listener != null) listener.onBatchStarted(batch);
        });
    }

    @Override
    public void onAllDone() {
        mainHandler.post(() -> {
            if (listener != null) listener.onUpdateAllFinished();
        });
        stopForeground(true);
        stopSelf();
    }

    private void notifyRow(SleepingApp app, String notificationText) {
        mainHandler.post(() -> {
            if (listener != null) listener.onRowUpdated(app);
        });
        updateNotification(notificationText);
    }

    private String getInstalledVersion(PackageManager pm, String packageName) {
        try {
            return pm.getPackageInfo(packageName, 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }
}
