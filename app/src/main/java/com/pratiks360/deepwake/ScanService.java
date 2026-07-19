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

/**
 * Runs scanning and waking/updating as a foreground service instead of on the Activity,
 * so both survive the app being minimised - a plain background process (no foreground
 * service) can be killed by Android at any point, which was silently aborting scans and
 * losing unsaved progress whenever the user switched away mid-scan.
 */
public class ScanService extends Service implements UpdateManager.Listener {

    private static final String CHANNEL_ID = "deepwake_activity";
    private static final int NOTIFICATION_ID = 1;

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
    private ExecutorService executor;
    private UpdateManager updateManager;
    private Listener listener;
    private volatile boolean scanning;

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor();
        updateManager = new UpdateManager(this, this);
        createChannel();
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
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION_ID, n);
    }

    public void startScan() {
        if (scanning) return;
        scanning = true;
        updateNotification("Scanning for sleeping apps...");
        if (listener != null) listener.onScanStarted();

        executor.execute(() -> {
            PackageManager pm = getPackageManager();

            // preserve any previously tracked entries not re-encountered this scan
            Map<String, SleepingApp> merged = new LinkedHashMap<>();
            for (SleepingApp a : AppListStorage.load(this)) merged.put(a.packageName, a);

            for (ApplicationInfo info : pm.getInstalledApplications(0)) {
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
                SleepingApp app = new SleepingApp(info.packageName, appName, current, "checking...");
                merged.put(info.packageName, app);
                notifyRow(app, "Scanning " + appName + "...");

                // fetch latest version (still on this background executor)
                app.latestVersion = PlayStoreVersionFetcher.fetchLatestVersion(info.packageName, current);
                notifyRow(app, "Scanning " + appName + "...");

                // Save after every app, not just at the end, so a mid-scan process kill
                // (Android may reclaim even a foreground service under extreme pressure)
                // loses at most the one app in flight, not the whole scan's progress.
                AppListStorage.save(this, new ArrayList<>(merged.values()));
            }

            scanning = false;
            mainHandler.post(() -> {
                if (listener != null) listener.onScanFinished();
            });
            stopForeground(true);
            stopSelf();
        });
    }

    public void startUpdateAll(List<SleepingApp> apps) {
        updateNotification("Waking " + apps.size() + " app(s)...");
        updateManager.updateAll(apps);
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
