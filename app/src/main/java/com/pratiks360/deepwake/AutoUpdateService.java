package com.pratiks360.deepwake;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Fully automated batch updating, SD-Maid style: while a batch run is active this service
 * draws a full-screen tint overlay that swallows all touches (so the user can't disturb
 * the flow - only the overlay's own Cancel button works), then drives the whole loop
 * itself: wake a batch of sleeping apps, open Play Store's updates page, auto-click its
 * "Update all"/"Update" button, wait for the updates to land, next batch - switching
 * between the woken apps and Play Store as needed until every selected app is done.
 *
 * Why an AccessibilityService: it is the only sanctioned mechanism that can (a) click
 * buttons inside another app (Play Store) and (b) draw a TYPE_ACCESSIBILITY_OVERLAY that
 * blocks input. Just as important, a system-bound accessibility service is on Android's
 * exemption list for background activity starts - so unlike the earlier attempts, the
 * delayed app/Play Store switches in this flow cannot be silently dropped, no matter how
 * long the run takes or which app is currently visible. The user must enable the service
 * once under Settings > Accessibility; MainActivity redirects there if it's off.
 *
 * The accessibility config (res/xml/auto_update_service.xml) restricts events to
 * com.android.vending only, so this service never sees content from any other app.
 */
public class AutoUpdateService extends AccessibilityService {

    private static final String TAG = "DeepWakeAuto";

    private static final int BATCH_SIZE = 4;
    private static final long STAGGER_MS = 800;       // between app launches in a batch
    private static final long SETTLE_MS = 600;        // after last launch, before Play Store
    private static final long VERIFY_INTERVAL_MS = 5000;
    private static final int MAX_VERIFY_TRIES = 24;   // ~2 min per batch
    private static final int MAX_FINAL_TRIES = 24;    // ~2 min extra for stragglers at the end
    private static final long CLICK_COOLDOWN_MS = 3000;
    private static final long POLL_INTERVAL_MS = 1200; // retry the click even without events
    private static final String PLAY_STORE_PKG = "com.android.vending";

    // Accessibility services are singletons managed by the system; this is the standard
    // way for the rest of the app to reach the live instance (null = not enabled).
    private static AutoUpdateService instance;

    public static AutoUpdateService getInstance() {
        return instance;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());

    private WindowManager windowManager;
    private LinearLayout overlay;
    private TextView overlayStatus;

    private List<List<SleepingApp>> batches;
    private int batchIndex;
    private final List<SleepingApp> leftovers = new ArrayList<>();
    private int updatedCount;
    private boolean running;
    private boolean autoClickArmed;
    private long lastClickAttempt;

    // While armed, keep retrying the click on a timer - Play Store's "Update all" button
    // appears only after the page finishes loading over the network, and relying solely on
    // accessibility events to catch that moment is unreliable (events can be missed or
    // throttled). The poll re-posts itself; CLICK_COOLDOWN_MS still rate-limits real taps.
    private final Runnable autoClickPoll = new Runnable() {
        @Override
        public void run() {
            if (!running || !autoClickArmed) return;
            driveToPlayStoreAndClick();
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // User switched the service off mid-run - clean everything up.
        cancelFlow(false);
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        cancelFlow(false);
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {
    }

    public boolean isRunning() {
        return running;
    }

    // ---------------------------------------------------------------- batch flow

    public void startBatchUpdate(List<SleepingApp> apps) {
        if (running || apps == null || apps.isEmpty()) return;
        running = true;
        updatedCount = 0;
        leftovers.clear();
        batches = chunk(apps, BATCH_SIZE);
        batchIndex = 0;
        showOverlay();
        handler.post(this::runNextBatch);
    }

    private List<List<SleepingApp>> chunk(List<SleepingApp> apps, int size) {
        List<List<SleepingApp>> out = new ArrayList<>();
        for (int i = 0; i < apps.size(); i += size) {
            out.add(new ArrayList<>(apps.subList(i, Math.min(i + size, apps.size()))));
        }
        return out;
    }

    private void runNextBatch() {
        if (!running) return;
        if (batchIndex >= batches.size()) {
            finalVerification();
            return;
        }
        // Don't click while we're busy launching apps - only once we're on Play Store.
        disarmAutoClick();
        List<SleepingApp> batch = batches.get(batchIndex);
        setOverlayStatus("Batch " + (batchIndex + 1) + "/" + batches.size()
                + ": waking " + batch.size() + " app(s)...");
        wakeOne(batch, 0);
    }

    private void wakeOne(List<SleepingApp> batch, int i) {
        if (!running) return;
        if (i >= batch.size()) {
            // The WHOLE batch is open now - only at this point switch to Play Store.
            handler.postDelayed(() -> {
                if (!running) return;
                openPlayStoreUpdates();
                setOverlayStatus("Batch " + (batchIndex + 1) + "/" + batches.size()
                        + ": waiting for Play Store to update " + batch.size() + " app(s)...");
                armAutoClick();
                handler.postDelayed(() -> verifyBatch(new ArrayList<>(batch), 0), VERIFY_INTERVAL_MS);
            }, SETTLE_MS);
            return;
        }
        launchApp(batch.get(i).packageName);
        handler.postDelayed(() -> wakeOne(batch, i + 1), STAGGER_MS);
    }

    private void armAutoClick() {
        autoClickArmed = true;
        handler.removeCallbacks(autoClickPoll);
        handler.postDelayed(autoClickPoll, POLL_INTERVAL_MS);
    }

    private void disarmAutoClick() {
        autoClickArmed = false;
        handler.removeCallbacks(autoClickPoll);
    }

    private void verifyBatch(List<SleepingApp> pending, int tryCount) {
        if (!running) return;
        List<SleepingApp> still = new ArrayList<>();
        for (SleepingApp app : pending) {
            if (isUpdated(app)) markUpdated(app);
            else still.add(app);
        }

        if (still.isEmpty() || tryCount + 1 >= MAX_VERIFY_TRIES) {
            // Timeout doesn't kill the run - unfinished apps get one more chance in the
            // final verification pass while later batches proceed.
            leftovers.addAll(still);
            batchIndex++;
            runNextBatch();
        } else {
            setOverlayStatus("Batch " + (batchIndex + 1) + "/" + batches.size()
                    + ": " + still.size() + " app(s) still updating...");
            handler.postDelayed(() -> verifyBatch(still, tryCount + 1), VERIFY_INTERVAL_MS);
        }
    }

    private void finalVerification() {
        if (!running) return;
        if (leftovers.isEmpty()) {
            finishFlow();
            return;
        }
        // Play Store is still in front from the last batch; keep clicking any Update button
        // that's still showing while the stragglers finish.
        armAutoClick();
        setOverlayStatus("Waiting for " + leftovers.size() + " remaining update(s)...");
        verifyLeftovers(new ArrayList<>(leftovers), 0);
    }

    private void verifyLeftovers(List<SleepingApp> pending, int tryCount) {
        if (!running) return;
        List<SleepingApp> still = new ArrayList<>();
        for (SleepingApp app : pending) {
            if (isUpdated(app)) markUpdated(app);
            else still.add(app);
        }
        if (still.isEmpty() || tryCount + 1 >= MAX_FINAL_TRIES) {
            leftovers.clear();
            leftovers.addAll(still);
            finishFlow();
        } else {
            setOverlayStatus("Waiting for " + still.size() + " remaining update(s)...");
            handler.postDelayed(() -> verifyLeftovers(still, tryCount + 1), VERIFY_INTERVAL_MS);
        }
    }

    private void finishFlow() {
        int pendingCount = leftovers.size();
        stopFlowInternal();
        Toast.makeText(this, "Batch update finished: " + updatedCount + " updated"
                + (pendingCount > 0 ? ", " + pendingCount + " still pending" : ""),
                Toast.LENGTH_LONG).show();
        bringDeepWakeToFront();
    }

    private void cancelFlow(boolean returnToApp) {
        if (!running && overlay == null) return;
        stopFlowInternal();
        if (returnToApp) bringDeepWakeToFront();
    }

    private void stopFlowInternal() {
        running = false;
        autoClickArmed = false;
        handler.removeCallbacksAndMessages(null);
        hideOverlay();
    }

    // ---------------------------------------------------------------- verification

    private boolean isUpdated(SleepingApp app) {
        String installed = getInstalledVersion(app.packageName);
        if (app.latestVersion != null && !app.latestVersion.isEmpty()
                && !app.latestVersion.equals(PlayStoreVersionFetcher.NET_ERROR)
                && !app.latestVersion.equals(PlayStoreVersionFetcher.NO_MATCH)) {
            return app.latestVersion.equals(installed);
        }
        return installed != null && !installed.equals(app.currentVersion);
    }

    private void markUpdated(SleepingApp app) {
        updatedCount++;
        List<SleepingApp> all = AppListStorage.load(this);
        all.removeIf(a -> a.packageName.equals(app.packageName));
        AppListStorage.save(this, all);
    }

    private String getInstalledVersion(String packageName) {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(packageName, 0);
            return pi.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- activity starts

    private void launchApp(String packageName) {
        PackageManager pm = getPackageManager();
        Intent intent = pm.getLaunchIntentForPackage(packageName);
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(intent);
            } catch (Exception e) {
                Log.w(TAG, "launch failed for " + packageName + ": " + e.getMessage());
            }
        }
    }

    private void openPlayStoreUpdates() {
        // Package pinned so this internal action resolves reliably and lands on the
        // "Downloads / Manage updates" screen (where the "Update all" button lives).
        Intent deepLink = new Intent("com.google.android.finsky.VIEW_MY_DOWNLOADS")
                .setPackage(PLAY_STORE_PKG)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (tryStart(deepLink)) return;
        // Deep link not handled on this Play Store build - at least bring Play Store up.
        Intent launch = getPackageManager().getLaunchIntentForPackage(PLAY_STORE_PKG);
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            tryStart(launch);
        }
    }

    private boolean tryStart(Intent intent) {
        // Don't gate on resolveActivity() - internal actions like VIEW_MY_DOWNLOADS often
        // won't resolve implicitly even though startActivity() launches them fine. Just try
        // it and let the exception path fall through to the caller's fallback.
        try {
            startActivity(intent);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "startActivity failed: " + e.getMessage());
            return false;
        }
    }

    private void bringDeepWakeToFront() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        tryStart(i);
    }

    // ---------------------------------------------------------------- auto-click

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Events are one trigger; the poll (armAutoClick) is the other. Both funnel here.
        driveToPlayStoreAndClick();
    }

    /**
     * The heart of the batch loop's reliability. Every tick (poll + accessibility event):
     *   1. If Play Store's updates screen isn't actually in front, (re)open it and stop.
     *      Launching several apps back-to-back can leave the last one stuck on top and the
     *      one-shot switch to Play Store can be dropped, so instead of switching once and
     *      hoping, we keep re-asserting Play Store until it's genuinely foreground.
     *   2. Only once Play Store IS in front do we click its "Update all"/"Update" button.
     */
    private void driveToPlayStoreAndClick() {
        if (!running || !autoClickArmed) return;

        if (!isPlayStoreForeground()) {
            openPlayStoreUpdates();
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastClickAttempt < CLICK_COOLDOWN_MS) return;
        if (clickUpdateInPlayStore()) lastClickAttempt = now;
    }

    private boolean isPlayStoreForeground() {
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows == null) return false;
        for (AccessibilityWindowInfo w : windows) {
            // Our own tint is a (non-focusable) accessibility overlay, so the real
            // foreground app stays the "active" application window - check that one.
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            if (!w.isActive()) continue;
            AccessibilityNodeInfo root = w.getRoot();
            if (root != null && root.getPackageName() != null
                    && PLAY_STORE_PKG.contentEquals(root.getPackageName())) {
                return true;
            }
        }
        return false;
    }

    private boolean clickUpdateInPlayStore() {
        // Walk every window and act only on Play Store's - getRootInActiveWindow() can hand
        // back our own overlay window instead, which has no Update button.
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows != null) {
            for (AccessibilityWindowInfo w : windows) {
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null) continue;
                CharSequence pkg = root.getPackageName();
                if (pkg == null || !PLAY_STORE_PKG.contentEquals(pkg)) continue;
                if (clickButtonLabeled(root, "Update all") || clickButtonLabeled(root, "Update")) {
                    return true;
                }
            }
        }
        // Fallback for devices/versions where getWindows() returns nothing usable.
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && root.getPackageName() != null
                && PLAY_STORE_PKG.contentEquals(root.getPackageName())) {
            return clickButtonLabeled(root, "Update all") || clickButtonLabeled(root, "Update");
        }
        return false;
    }

    private boolean clickButtonLabeled(AccessibilityNodeInfo root, String label) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(label);
        if (nodes == null) return false;
        for (AccessibilityNodeInfo node : nodes) {
            // findAccessibilityNodeInfosByText is a case-insensitive SUBSTRING match, so
            // "Update" would also hit "Updated on Jul 18" or "Updates available" - require
            // the node's own label to be exactly the button text before clicking anything.
            CharSequence text = node.getText();
            if (text == null || !text.toString().trim().equalsIgnoreCase(label)) continue;

            AccessibilityNodeInfo target = node;
            for (int depth = 0; target != null && depth < 5; depth++) {
                if (target.isClickable() && target.isEnabled()) {
                    return target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }
                target = target.getParent();
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- overlay

    private void showOverlay() {
        if (overlay != null) return;
        try {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            box.setBackgroundColor(Color.parseColor("#A6000000"));
            int pad = (int) (24 * getResources().getDisplayMetrics().density);
            box.setPadding(pad, pad, pad, pad);
            // The layout consumes every touch that lands on it, which is exactly what
            // blocks the user's input to whatever is underneath while the flow runs.
            box.setClickable(true);

            // Indeterminate spinner - a live "working" animation for the whole run.
            ProgressBar spinner = new ProgressBar(this);
            spinner.setIndeterminate(true);
            LinearLayout.LayoutParams spinnerLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            spinnerLp.bottomMargin = pad;
            spinner.setLayoutParams(spinnerLp);
            box.addView(spinner);

            overlayStatus = new TextView(this);
            overlayStatus.setTextColor(Color.WHITE);
            overlayStatus.setTextSize(18);
            overlayStatus.setGravity(Gravity.CENTER);
            overlayStatus.setText("DeepWake is updating your apps...\nPlease don't touch the screen.");
            box.addView(overlayStatus);

            TextView hint = new TextView(this);
            hint.setTextColor(Color.parseColor("#BBBBBB"));
            hint.setTextSize(13);
            hint.setGravity(Gravity.CENTER);
            hint.setPadding(0, pad / 2, 0, pad);
            hint.setText("Apps will flash on screen while they are woken.");
            box.addView(hint);

            Button cancel = new Button(this);
            cancel.setText("Cancel");
            cancel.setOnClickListener(v -> cancelFlow(true));
            box.addView(cancel);

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            windowManager.addView(box, lp);
            overlay = box;

            // Fade + slight scale-up so the shade eases in rather than snapping on.
            box.setAlpha(0f);
            box.setScaleX(1.04f);
            box.setScaleY(1.04f);
            box.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start();
        } catch (Exception e) {
            // Overlay is cosmetic protection - never let it break the update flow itself.
            Log.w(TAG, "overlay failed: " + e.getMessage());
            overlay = null;
        }
    }

    private void hideOverlay() {
        if (overlay != null && windowManager != null) {
            try {
                windowManager.removeView(overlay);
            } catch (Exception ignored) {
            }
        }
        overlay = null;
        overlayStatus = null;
    }

    private void setOverlayStatus(String text) {
        if (overlayStatus != null) {
            overlayStatus.setText("DeepWake is updating your apps...\n\n" + text);
        }
    }
}
