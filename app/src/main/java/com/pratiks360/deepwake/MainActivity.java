package com.pratiks360.deepwake;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity implements ScanService.Listener {

    // Only outdated (or still-being-checked) apps are shown - sleeping apps that are
    // already current aren't actionable, so there's no point cluttering the list with them.
    private final List<SleepingApp> appList = new ArrayList<>();
    private final Set<String> trackedPackages = new HashSet<>();
    private AppListAdapter adapter;
    private Button btnScan, btnUpdateAll;
    private CheckBox cbSelectAll;
    private TextView statusText;

    private ScanService scanService;
    private boolean bound;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            scanService = ((ScanService.LocalBinder) service).getService();
            scanService.setListener(MainActivity.this);
            bound = true;
            setScanning(scanService.isScanning());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            scanService = null;
            bound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnScan = findViewById(R.id.btnScan);
        btnUpdateAll = findViewById(R.id.btnUpdateAll);
        cbSelectAll = findViewById(R.id.cbSelectAll);
        statusText = findViewById(R.id.statusText);

        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AppListAdapter(appList, app -> {
            if (scanService != null) scanService.startUpdateSingle(app);
        });
        recyclerView.setAdapter(adapter);

        reloadFromStorage();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        btnScan.setOnClickListener(v -> {
            setScanning(true);
            ContextCompat.startForegroundService(this, new Intent(this, ScanService.class));
            if (scanService != null) scanService.startScan();
        });
        cbSelectAll.setOnCheckedChangeListener((btn, checked) -> {
            for (SleepingApp a : appList) a.selected = checked;
            adapter.notifyDataSetChanged();
        });
        btnUpdateAll.setOnClickListener(v -> {
            List<SleepingApp> selected = selectedOutdated();
            if (selected.isEmpty()) {
                Toast.makeText(this, "No apps selected for update", Toast.LENGTH_SHORT).show();
                return;
            }
            AutoUpdateService svc = AutoUpdateService.getInstance();
            if (svc == null) {
                // Batch mode is fully automated (auto-clicking Play Store's buttons, the
                // touch-blocking shade) and that requires the accessibility service.
                Toast.makeText(this,
                        "Enable \"DeepWake\" in Accessibility settings to allow automatic batch updates",
                        Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                return;
            }
            if (svc.isRunning()) {
                Toast.makeText(this, "A batch update is already running", Toast.LENGTH_SHORT).show();
                return;
            }
            svc.startBatchUpdate(selected);
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, ScanService.class), connection, Context.BIND_AUTO_CREATE);
        // The service may have kept scanning/saving while this Activity was unbound.
        reloadFromStorage();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (bound) {
            scanService.setListener(null);
            unbindService(connection);
            bound = false;
        }
    }

    private void reloadFromStorage() {
        List<SleepingApp> all = AppListStorage.load(this);
        trackedPackages.clear();
        appList.clear();
        boolean selectAll = cbSelectAll == null || cbSelectAll.isChecked();
        for (SleepingApp a : all) {
            trackedPackages.add(a.packageName);
            if (isOutdated(a)) {
                a.selected = selectAll;
                appList.add(a);
            }
        }
        adapter.notifyDataSetChanged();
        updateStatus();
    }

    /** Checked apps whose latest version is known AND is a real, strictly newer version. */
    private List<SleepingApp> selectedOutdated() {
        List<SleepingApp> out = new ArrayList<>();
        for (SleepingApp a : appList) {
            if (a.selected && isOutdated(a)) out.add(a);
        }
        return out;
    }

    private boolean isOutdated(SleepingApp a) {
        if (a.latestVersion == null || a.latestVersion.isEmpty()) return false;
        if (a.latestVersion.equals(PlayStoreVersionFetcher.NET_ERROR)
                || a.latestVersion.equals(PlayStoreVersionFetcher.NO_MATCH)) return false;
        return PlayStoreVersionFetcher.isNewerVersion(a.latestVersion, a.currentVersion);
    }

    private void updateStatus() {
        int outdated = 0;
        for (SleepingApp a : appList) if (isOutdated(a)) outdated++;
        statusText.setText(outdated + " outdated / " + trackedPackages.size() + " sleeping tracked");
    }

    private void setScanning(boolean scanning) {
        btnScan.setEnabled(!scanning);
        btnScan.setText(scanning ? "Scanning..." : "Scan for updates");
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

    private void removeRow(String packageName) {
        for (int i = 0; i < appList.size(); i++) {
            if (appList.get(i).packageName.equals(packageName)) {
                appList.remove(i);
                adapter.notifyItemRemoved(i);
                return;
            }
        }
    }

    @Override
    public void onScanStarted() {
        setScanning(true);
    }

    @Override
    public void onRowUpdated(SleepingApp app) {
        trackedPackages.add(app.packageName);
        boolean checking = "checking...".equals(app.latestVersion);
        if (checking || isOutdated(app)) {
            addOrUpdateRow(app);
        } else {
            removeRow(app.packageName);
        }
        updateStatus();
    }

    @Override
    public void onScanFinished() {
        setScanning(false);
        reloadFromStorage();
    }

    @Override
    public void onAppUpdated(SleepingApp app) {
        trackedPackages.remove(app.packageName);
        appList.removeIf(a -> a.packageName.equals(app.packageName));
        adapter.notifyDataSetChanged();
        updateStatus();
        Toast.makeText(this, app.appName + " updated", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBatchStarted(List<SleepingApp> batch) {
        Toast.makeText(this, "Waking " + batch.size() + " app(s)...", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onUpdateAllFinished() {
        btnUpdateAll.setEnabled(true);
        Toast.makeText(this, "Update run finished", Toast.LENGTH_SHORT).show();
    }
}
