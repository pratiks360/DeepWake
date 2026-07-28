package com.pratiks360.deepwake;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity implements ScanService.Listener {

    // Only outdated (or still-being-checked) apps are shown - sleeping apps that are
    // already current aren't actionable, so there's no point cluttering the list with them.
    private final List<SleepingApp> appList = new ArrayList<>();
    private final Set<String> trackedPackages = new HashSet<>();
    private AppListAdapter adapter;
    private RecyclerView recyclerView;
    private MaterialButton btnScan, btnUpdateAll;
    private MaterialCheckBox cbSelectAll;
    private TextView statusText, statusHeadline;
    private View emptyState;

    private ScanService scanService;
    private boolean bound;
    private boolean scanning;
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
        // Android 12+ recolours the app from the user's wallpaper; older versions keep the
        // Material 3 baseline. Must run before setContentView.
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        btnScan = findViewById(R.id.btnScan);
        btnUpdateAll = findViewById(R.id.btnUpdateAll);
        cbSelectAll = findViewById(R.id.cbSelectAll);
        statusText = findViewById(R.id.statusText);
        statusHeadline = findViewById(R.id.statusHeadline);
        emptyState = findViewById(R.id.emptyState);

        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AppListAdapter(appList, app -> {
            if (scanService != null) scanService.startUpdateSingle(app);
        }, this::confirmExclude, this::updateSelectionCount);
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
            updateSelectionCount();
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
                showAccessibilityHelpDialog();
                return;
            }
            if (svc.isRunning()) {
                Toast.makeText(this, "A batch update is already running", Toast.LENGTH_SHORT).show();
                return;
            }
            svc.startBatchUpdate(selected);
        });

        maybeShowBatchReport(getIntent());
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_reports) {
            showReportHistory();
            return true;
        }
        if (id == R.id.menu_excluded) {
            showExcludedApps();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        maybeShowBatchReport(intent);
    }

    /**
     * AutoUpdateService relaunches this activity when a batch run finishes, carrying the id
     * of the report it just wrote; look that report up and show it. Only the id travels in
     * the Intent - the report itself lives in the DB, which is what makes it reopenable later
     * from the history. The extra is cleared afterwards so a config change / recreation
     * doesn't replay the same report.
     */
    private void maybeShowBatchReport(Intent intent) {
        if (intent == null || !intent.hasExtra(AutoUpdateService.EXTRA_REPORT_ID)) return;
        long id = intent.getLongExtra(AutoUpdateService.EXTRA_REPORT_ID, -1);
        intent.removeExtra(AutoUpdateService.EXTRA_REPORT_ID);
        for (BatchReport report : AppRepository.loadRecentReports(this)) {
            if (report.id == id) {
                showReport(report);
                break;
            }
        }
        // The batch removed updated apps from storage while we were unbound - refresh.
        reloadFromStorage();
    }

    /** The stored runs, newest first - one row per run, tap a row to read that report. */
    private void showReportHistory() {
        List<BatchReport> reports = AppRepository.loadRecentReports(this);
        if (reports.isEmpty()) {
            Toast.makeText(this, "No batch updates have finished yet", Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence[] rows = new CharSequence[reports.size()];
        for (int i = 0; i < reports.size(); i++) rows[i] = reports.get(i).rowLabel();
        new MaterialAlertDialogBuilder(this)
                .setTitle("Recent update runs")
                .setItems(rows, (d, which) -> showReport(reports.get(which)))
                .setNegativeButton("Close", null)
                .show();
    }

    private void showReport(BatchReport report) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(report.title())
                .setMessage(report.body())
                .setPositiveButton("Done", null)
                .setNeutralButton("All runs", (d, w) -> showReportHistory())
                .show();
    }

    /**
     * Long-press on a row. Excluding is deliberately a confirmed action rather than a swipe:
     * it's sticky, and it takes the app out of scanning entirely, so an accidental one would
     * quietly stop DeepWake from ever looking at that app again.
     */
    private void confirmExclude(SleepingApp app) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Exclude " + app.appName + "?")
                .setMessage("DeepWake will stop tracking it and skip it on every scan from "
                        + "now on. You can undo this from \"Excluded apps\" in the menu.")
                .setPositiveButton("Exclude", (d, w) -> {
                    AppRepository.excludeApp(this, app.packageName, app.appName);
                    trackedPackages.remove(app.packageName);
                    removeRow(app.packageName);
                    updateStatus();
                    Toast.makeText(this, app.appName + " excluded", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** The exclusion list, with a tick against each app to bring back. */
    private void showExcludedApps() {
        List<ExcludedApp> excluded = AppRepository.loadExcluded(this);
        if (excluded.isEmpty()) {
            Toast.makeText(this, "No apps are excluded. Long-press an app to exclude it.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        CharSequence[] names = new CharSequence[excluded.size()];
        boolean[] restore = new boolean[excluded.size()];
        for (int i = 0; i < excluded.size(); i++) names[i] = excluded.get(i).appName;

        new MaterialAlertDialogBuilder(this)
                .setTitle("Excluded apps (" + excluded.size() + ")")
                .setMultiChoiceItems(names, restore, (d, which, checked) -> restore[which] = checked)
                .setPositiveButton("Stop excluding", (d, w) -> {
                    int count = 0;
                    for (int i = 0; i < excluded.size(); i++) {
                        if (!restore[i]) continue;
                        AppRepository.includeApp(this, excluded.get(i).packageName);
                        count++;
                    }
                    if (count > 0) {
                        // They only come back as rows once a scan has looked them up again.
                        Toast.makeText(this, count + " app(s) restored - run a scan to "
                                + "pick them up", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    /**
     * On Android 13+ a sideloaded app's accessibility toggle is blocked behind
     * "Restricted settings" (the "allow restricted apps" prompt the toggle shows), so plain
     * "go flip the switch" instructions dead-end. Walk the user through the unblock too.
     */
    private void showAccessibilityHelpDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Enable the DeepWake service")
                .setMessage("Automatic batch updates need DeepWake's accessibility service.\n\n"
                        + "1. Open Accessibility settings\n"
                        + "2. Find DeepWake and turn it on\n\n"
                        + "If the toggle is blocked with a \"Restricted setting\" message "
                        + "(normal for apps installed outside an app store on Android 13+):\n\n"
                        + "1. Tap App info below\n"
                        + "2. Tap the ⋮ menu (top right)\n"
                        + "3. Tap \"Allow restricted settings\"\n"
                        + "4. Come back and enable the service")
                .setPositiveButton("Accessibility settings", (d, w) ->
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                .setNeutralButton("App info", (d, w) ->
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName()))))
                .setNegativeButton("Cancel", null)
                .show();
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
        List<SleepingApp> all = AppRepository.loadApps(this);
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
        // Re-run the staggered cascade so a fresh scan's results animate in, not just the
        // very first layout (layoutAnimation otherwise only fires once, on initial attach).
        if (recyclerView != null) recyclerView.scheduleLayoutAnimation();
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
        return PlayStoreVersionFetcher.isUsableVersion(a.latestVersion)
                && PlayStoreVersionFetcher.isNewerVersion(a.latestVersion, a.currentVersion);
    }

    private void updateStatus() {
        int outdated = 0;
        for (SleepingApp a : appList) if (isOutdated(a)) outdated++;
        statusHeadline.setText(outdated == 0
                ? getString(R.string.dash)
                : String.valueOf(outdated));
        statusText.setText(outdated == 0
                ? trackedPackages.size() + " sleeping apps tracked, none outdated"
                : (outdated == 1 ? "app to update" : "apps to update")
                        + " · " + trackedPackages.size() + " sleeping apps tracked");
        // The empty state replaces the list, not the summary - the counts still matter.
        // Not while a scan is running, though: rows stream in one at a time, and "Nothing to
        // update" on top of a scan that hasn't finished is just wrong.
        boolean empty = appList.isEmpty() && !scanning;
        if (emptyState != null) emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (recyclerView != null) recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
        updateSelectionCount();
    }

    /** Keeps the Update button showing how many apps are currently ticked. */
    private void updateSelectionCount() {
        int count = selectedOutdated().size();
        btnUpdateAll.setText(count > 0 ? "Update (" + count + ")" : "Update");
        btnUpdateAll.setEnabled(count > 0);
    }

    private void setScanning(boolean scanning) {
        this.scanning = scanning;
        btnScan.setEnabled(!scanning);
        btnScan.setText(scanning ? R.string.action_scanning : R.string.action_scan);
        updateStatus();
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
        boolean checking = PlayStoreVersionFetcher.CHECKING.equals(app.latestVersion);
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
