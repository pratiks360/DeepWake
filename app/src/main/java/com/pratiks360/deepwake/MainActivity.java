package com.pratiks360.deepwake;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements UpdateManager.Listener {

    private final List<SleepingApp> appList = new ArrayList<>();
    private AppListAdapter adapter;
    private ExecutorService executor;
    private Handler mainHandler;
    private UpdateManager updateManager;
    private Button btnScan, btnUpdateAll;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        executor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
        updateManager = new UpdateManager(this, this);

        btnScan = findViewById(R.id.btnScan);
        btnUpdateAll = findViewById(R.id.btnUpdateAll);
        statusText = findViewById(R.id.statusText);

        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AppListAdapter(appList, app -> updateManager.updateSingle(app));
        recyclerView.setAdapter(adapter);

        appList.addAll(AppListStorage.load(this));
        adapter.notifyDataSetChanged();
        updateStatus();

        btnScan.setOnClickListener(v -> scanForSleepingApps());
        btnUpdateAll.setOnClickListener(v -> {
            List<SleepingApp> outdated = outdatedOnly();
            if (outdated.isEmpty()) {
                Toast.makeText(this, "No outdated apps to update", Toast.LENGTH_SHORT).show();
                return;
            }
            btnUpdateAll.setEnabled(false);
            updateManager.updateAll(outdated);
        });
    }

    /** Apps whose latest version is known AND differs from the installed version. */
    private List<SleepingApp> outdatedOnly() {
        List<SleepingApp> out = new ArrayList<>();
        for (SleepingApp a : appList) {
            if (isOutdated(a)) out.add(a);
        }
        return out;
    }

    private boolean isOutdated(SleepingApp a) {
        if (a.latestVersion == null || a.latestVersion.isEmpty()) return false;
        if (a.latestVersion.equals(PlayStoreVersionFetcher.NET_ERROR)
                || a.latestVersion.equals(PlayStoreVersionFetcher.NO_MATCH)) return false;
        return !a.latestVersion.equals(a.currentVersion);
    }

    private void updateStatus() {
        int outdated = 0;
        for (SleepingApp a : appList) if (isOutdated(a)) outdated++;
        statusText.setText(outdated + " outdated / " + appList.size() + " sleeping tracked");
    }

    private void setScanning(boolean scanning) {
        btnScan.setEnabled(!scanning);
        btnScan.setText(scanning ? "Scanning..." : "Scan for updates");
    }

    /**
     * Live scan: finds deep-sleeping, Play-Store-installed, non-system apps and adds each
     * to the list AS IT IS FOUND (with latest = "checking..."), then fetches the Play Store
     * latest version per app in the background and updates that row when it arrives.
     */
    private void scanForSleepingApps() {
        setScanning(true);
        executor.execute(() -> {
            PackageManager pm = getPackageManager();

            // preserve any previously tracked entries
            Map<String, SleepingApp> merged = new LinkedHashMap<>();
            for (SleepingApp a : AppListStorage.load(this)) merged.put(a.packageName, a);

            mainHandler.post(() -> {
                appList.clear();
                adapter.notifyDataSetChanged();
                updateStatus();
            });

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

                mainHandler.post(() -> {
                    addOrUpdateRow(app);
                    updateStatus();
                });

                // fetch latest version (still on this background executor)
                String latest = PlayStoreVersionFetcher.fetchLatestVersion(info.packageName);
                app.latestVersion = latest;

                mainHandler.post(() -> {
                    addOrUpdateRow(app);
                    updateStatus();
                });
            }

            List<SleepingApp> result = new ArrayList<>(merged.values());
            AppListStorage.save(this, result);
            mainHandler.post(() -> setScanning(false));
        });
    }

    private void addOrUpdateRow(SleepingApp app) {
        for (int i = 0; i < appList.size(); i++) {
            if (appList.get(i).packageName.equals(app.packageName)) {
                appList.set(i, app);
                adapter.notifyItemChanged(i);
                return;
            }
        }
        appList.add(app);
        adapter.notifyItemInserted(appList.size() - 1);
    }

    private String getInstalledVersion(PackageManager pm, String packageName) {
        try {
            return pm.getPackageInfo(packageName, 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    @Override
    public void onAppUpdated(SleepingApp app) {
        appList.removeIf(a -> a.packageName.equals(app.packageName));
        AppListStorage.save(this, appList);
        adapter.notifyDataSetChanged();
        updateStatus();
        Toast.makeText(this, app.appName + " updated", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBatchStarted(List<SleepingApp> batch) {
        Toast.makeText(this, "Waking " + batch.size() + " app(s)...", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onAllDone() {
        btnUpdateAll.setEnabled(true);
        Toast.makeText(this, "Update run finished", Toast.LENGTH_SHORT).show();
    }
}