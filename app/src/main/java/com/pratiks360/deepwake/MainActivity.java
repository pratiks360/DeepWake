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
import java.util.HashMap;
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
            if (appList.isEmpty()) {
                Toast.makeText(this, "List is empty - scan first", Toast.LENGTH_SHORT).show();
                return;
            }
            btnUpdateAll.setEnabled(false);
            updateManager.updateAll(new ArrayList<>(appList));
        });
    }

    private void updateStatus() {
        statusText.setText(appList.size() + " sleeping app(s) tracked");
    }

    private void setScanning(boolean scanning) {
        btnScan.setEnabled(!scanning);
        btnScan.setText(scanning ? "Scanning..." : "Scan for updates");
    }

    /**
     * Finds deep-sleeping, Play-Store-installed, non-system apps; reads their installed
     * version; scrapes the Play Store listing for the latest version; merges the result
     * into the persisted list (so previously found apps are kept/refreshed, not lost).
     */
    private void scanForSleepingApps() {
        setScanning(true);
        executor.execute(() -> {
            PackageManager pm = getPackageManager();
            Map<String, SleepingApp> merged = new HashMap<>();
            for (SleepingApp a : AppListStorage.load(this)) {
                merged.put(a.packageName, a);
            }

            for (ApplicationInfo info : pm.getInstalledApplications(0)) {
                if ((info.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                if (info.enabled) continue; // only deep-sleeping (disabled) apps
                String installer = pm.getInstallerPackageName(info.packageName);
                if (!"com.android.vending".equals(installer)) continue;

                String currentVersion = getInstalledVersion(pm, info.packageName);
                String latestVersion = PlayStoreVersionFetcher.fetchLatestVersion(info.packageName);
                String appName = String.valueOf(pm.getApplicationLabel(info));

                merged.put(info.packageName, new SleepingApp(info.packageName, appName, currentVersion, latestVersion));
            }

            List<SleepingApp> result = new ArrayList<>(merged.values());
            AppListStorage.save(this, result);

            mainHandler.post(() -> {
                appList.clear();
                appList.addAll(result);
                adapter.notifyDataSetChanged();
                updateStatus();
                setScanning(false);
            });
        });
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
