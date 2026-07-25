package com.pratiks360.deepwake;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityManager;
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
import java.util.Iterator;
import java.util.List;

/**
 * Fully automated batch updating, SD-Maid style: while a batch run is active this service
 * draws a full-screen tint overlay that swallows all touches (so the user can't disturb
 * the flow - only the overlay's own Cancel button works), then drives the whole loop
 * itself. The selected apps are worked through in sequential batches of BATCH_SIZE: wake
 * the current batch's apps, open Play Store's updates page, auto-click its "Update all"
 * button, then monitor that batch until each app's installed version actually moves -
 * switching between the woken apps and Play Store as needed. Only when the current batch
 * completes (or stalls) does the next batch of apps get woken; waking 50 apps at once
 * just thrashes the device and re-hibernation undoes most of them before Play Store gets
 * there anyway. On aggressive-hibernation devices a woken app can still slip back to sleep
 * mid-update (Play Store then stalls it), so while waiting we periodically re-wake any
 * pending app in the current batch that has gone back to sleep and re-tap Update all - see
 * maybeRewake. A batch that makes no progress for a while (or hits its hard time cap) is
 * skipped so the run always moves on and finishes on its own - the overlay never sits on
 * Play Store forever waiting for the user to cancel.
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

    private static final int BATCH_SIZE = 4;          // apps woken/updated at a time
    private static final long STAGGER_MS = 700;       // between app launches
    private static final long SETTLE_MS = 800;        // after last launch, before Play Store
    private static final long VERIFY_INTERVAL_MS = 5000;
    private static final int BATCH_MAX_TICKS = 180;   // ~15 min hard cap PER BATCH, then skip
    private static final int STALL_TICKS = 36;        // ~3 min zero progress -> skip batch
                                                      // (Play Store installs one app at a
                                                      // time, so a big app can hold progress)
    private static final int REWAKE_EVERY_TICKS = 3;  // re-wake re-slept apps ~every 15s
    private static final int RESTART_PS_TICKS = 9;    // ~45s zero progress with everything
                                                      // awake -> kill + reopen Play Store
    private static final long REOPEN_COOLDOWN_MS = 2500; // min gap between re-opening Play Store
    private static final long REOPEN_SETTLED_COOLDOWN_MS = 12000; // ...while Play Store is front
    private static final long POLL_INTERVAL_MS = 1200;   // retry the click even without events
    private static final String PLAY_STORE_PKG = "com.android.vending";

    // Extras on the MainActivity relaunch intent carrying the finished run's report.
    public static final String EXTRA_REPORT_UPDATED = "com.pratiks360.deepwake.REPORT_UPDATED";
    public static final String EXTRA_REPORT_NOT_UPDATED = "com.pratiks360.deepwake.REPORT_NOT_UPDATED";

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

    // queue holds the selected apps not yet attempted; pending is the CURRENT batch's
    // not-yet-updated apps (at most BATCH_SIZE). All the wake/monitor/re-wake machinery
    // below operates on pending only - the queue feeds it one batch at a time.
    private final List<SleepingApp> queue = new ArrayList<>();
    private final List<SleepingApp> pending = new ArrayList<>();
    private final ArrayList<String> updatedNames = new ArrayList<>();
    private final ArrayList<String> skippedNames = new ArrayList<>();
    private int totalSelected;
    private int totalBatches;
    private int batchNumber;      // 1-based, for the overlay status
    private int updatedCount;
    private int skippedCount;     // apps abandoned when their batch stalled / hit the cap
    private int lastProgressTick; // tick (within the current batch) of the last update
    private boolean running;
    private boolean autoClickArmed;
    private long lastReopenAttempt;

    // While armed, keep retrying on a timer - Play Store's "Update all" button appears only
    // after the page finishes loading over the network, and relying solely on accessibility
    // events to catch that moment is unreliable (events can be missed or throttled). The
    // poll re-posts itself; see driveToPlayStoreAndClick for the click-then-reopen logic.
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
        skippedCount = 0;
        batchNumber = 0;
        updatedNames.clear();
        skippedNames.clear();
        queue.clear();
        queue.addAll(apps);
        totalSelected = queue.size();
        totalBatches = (totalSelected + BATCH_SIZE - 1) / BATCH_SIZE;
        showOverlay();
        startNextBatch();
    }

    /** Pull the next BATCH_SIZE apps off the queue and run them; finish when it's empty. */
    private void startNextBatch() {
        if (!running) return;
        pending.clear();
        while (pending.size() < BATCH_SIZE && !queue.isEmpty()) {
            pending.add(queue.remove(0));
        }
        if (pending.isEmpty()) {
            finishFlow();
            return;
        }
        batchNumber++;
        lastProgressTick = 0;
        disarmAutoClick(); // no Play Store taps while the new batch's apps are being woken
        handler.post(() -> wakeAll(0));
    }

    /** Wake the current batch's apps (staggered), then hand off to Play Store + monitor. */
    private void wakeAll(int i) {
        if (!running) return;
        if (i >= pending.size()) {
            handler.postDelayed(() -> {
                if (!running) return;
                openPlayStoreUpdates();
                armAutoClick();
                setOverlayStatus(batchLabel() + "Waiting for Play Store to update "
                        + pending.size() + " app(s)...");
                handler.postDelayed(() -> monitor(0), VERIFY_INTERVAL_MS);
            }, SETTLE_MS);
            return;
        }
        setOverlayStatus(batchLabel() + "Waking apps... (" + (i + 1) + "/" + pending.size() + ")");
        launchApp(pending.get(i).packageName);
        handler.postDelayed(() -> wakeAll(i + 1), STAGGER_MS);
    }

    /**
     * Loop over the CURRENT batch: sweep for completed updates, re-wake any app that
     * slipped back to sleep, keep Play Store tapping "Update all". When the batch empties
     * it rolls straight into the next one; a batch with no progress for a while (or at its
     * hard cap) is skipped so one stuck app can't wedge the rest of the queue - either way
     * the overlay always dismisses itself instead of sitting on Play Store forever.
     */
    private void monitor(int tick) {
        if (!running) return;

        boolean progressed = false;
        Iterator<SleepingApp> it = pending.iterator();
        while (it.hasNext()) {
            SleepingApp app = it.next();
            if (isUpdated(app)) {
                markUpdated(app); // increments updatedCount + drops it from stored list
                it.remove();
                progressed = true;
            }
        }
        if (progressed) lastProgressTick = tick;

        setOverlayStatus(batchLabel() + updatedCount + "/" + totalSelected + " updated, "
                + pending.size() + " in this batch...");

        if (pending.isEmpty()) {
            startNextBatch();
            return;
        }
        // Skip this batch if it hit its hard cap, or if nothing has updated for a while AND
        // nothing is still asleep (so re-waking has nothing left to try - Play Store simply
        // has no more updates to give, e.g. false-positive "outdated" entries).
        boolean stalled = (tick - lastProgressTick) >= STALL_TICKS && allAwake();
        if (tick + 1 >= BATCH_MAX_TICKS || stalled) {
            skippedCount += pending.size();
            for (SleepingApp app : pending) skippedNames.add(app.appName);
            startNextBatch();
            return;
        }

        if (shouldRestartPlayStore(tick)) {
            restartPlayStore();
        } else {
            maybeRewake(tick);
        }
        handler.postDelayed(() -> monitor(tick + 1), VERIFY_INTERVAL_MS);
    }

    /**
     * Play Store's Overview page can get stuck on a stale "All apps up to date" while an
     * awake app still has an update pending (its cache never re-lists the app). Re-wake
     * can't help there - the apps ARE awake - so after every ~RESTART_PS_TICKS of zero
     * progress with everything awake, kill Play Store's process and reopen it, forcing a
     * cold reload of the updates list.
     */
    private boolean shouldRestartPlayStore(int tick) {
        int idle = tick - lastProgressTick;
        return idle >= RESTART_PS_TICKS && idle % RESTART_PS_TICKS == 0 && allAwake();
    }

    private void restartPlayStore() {
        disarmAutoClick();
        setOverlayStatus(batchLabel() + "Play Store looks stuck - restarting it...");
        // Play Store is foreground (under our overlay); killBackgroundProcesses only kills
        // background processes, so shove it to the background with HOME first.
        performGlobalAction(GLOBAL_ACTION_HOME);
        handler.postDelayed(() -> {
            if (!running) return;
            try {
                ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
                am.killBackgroundProcesses(PLAY_STORE_PKG);
            } catch (Exception e) {
                // Even if the kill is denied/no-op, reopening still re-focuses the page and
                // the pull-to-refresh in the rewake path can catch it on a later tick.
                Log.w(TAG, "killBackgroundProcesses failed: " + e.getMessage());
            }
            // Re-launch every pending app BEFORE the cold reload. Between the restart
            // decision and Play Store's re-check an app can slip back to sleep (and some
            // sleep states never flip the enabled flag, so allAwake() can't see them);
            // if it's asleep when the fresh page loads, Play Store truthfully reports
            // "All apps up to date" and the batch wedges on that screen. Launching an
            // already-awake app costs only a brief flash, so wake them all unconditionally.
            rewakePendingThenReopen(0);
        }, 800);
    }

    private void rewakePendingThenReopen(int i) {
        if (!running) return;
        if (i >= pending.size()) {
            handler.postDelayed(() -> {
                if (!running) return;
                openPlayStoreUpdates();
                // A cold start can still render the updates list from cache; nudge an
                // explicit re-check once the page has had a moment to load.
                handler.postDelayed(this::refreshPlayStore, 2500);
                armAutoClick();
            }, SETTLE_MS);
            return;
        }
        launchApp(pending.get(i).packageName);
        handler.postDelayed(() -> rewakePendingThenReopen(i + 1), STAGGER_MS);
    }

    private String batchLabel() {
        return totalBatches > 1 ? "Batch " + batchNumber + " of " + totalBatches + "\n" : "";
    }

    private void armAutoClick() {
        autoClickArmed = true;
        // Play Store was just opened; give it the cooldown before the poll re-opens it.
        lastReopenAttempt = System.currentTimeMillis();
        handler.removeCallbacks(autoClickPoll);
        handler.postDelayed(autoClickPoll, POLL_INTERVAL_MS);
    }

    private void disarmAutoClick() {
        autoClickArmed = false;
        handler.removeCallbacks(autoClickPoll);
    }

    /**
     * On aggressive-hibernation devices a woken app slips back to sleep while it waits in the
     * background to update, and Play Store then stalls/skips it. Every few ticks we re-wake
     * any pending app that has gone back to sleep and hand the foreground back to Play Store -
     * the back-and-forth that keeps the whole selected list moving toward updated.
     */
    private void maybeRewake(int tick) {
        if ((tick + 1) % REWAKE_EVERY_TICKS != 0) return;
        List<SleepingApp> asleep = new ArrayList<>();
        for (SleepingApp app : pending) {
            if (isAsleep(app.packageName)) asleep.add(app);
        }
        if (asleep.isEmpty()) return; // nothing re-slept; the auto-click poll carries on
        disarmAutoClick(); // don't tap Play Store while we're flipping through the apps
        rewakeStep(asleep, 0);
    }

    private void rewakeStep(List<SleepingApp> asleep, int i) {
        if (!running) return;
        if (i >= asleep.size()) {
            // All re-woken - return to Play Store, force it to RE-CHECK for updates so the
            // apps that slept (and dropped off the cached "Available updates" list) show up
            // again, then re-tap Update all.
            openPlayStoreUpdates();
            handler.postDelayed(this::refreshPlayStore, 1500); // let Downloads load first
            armAutoClick();
            return;
        }
        launchApp(asleep.get(i).packageName);
        handler.postDelayed(() -> rewakeStep(asleep, i + 1), STAGGER_MS);
    }

    /**
     * Forces Play Store to re-scan for updates. When a woken app falls back asleep mid-run
     * Play Store drops it from the cached Downloads list and won't re-add it on its own;
     * re-launching the app un-sleeps it but Play Store still needs a refresh to notice.
     * Tries a "Check for updates" control if the build has one, else pull-to-refresh
     * (scroll the list up), which triggers the SwipeRefresh on the Downloads screen.
     */
    private void refreshPlayStore() {
        if (!running) return;
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows == null) return;
        for (AccessibilityWindowInfo w : windows) {
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null) continue;
            CharSequence pkg = root.getPackageName();
            if (pkg == null || !PLAY_STORE_PKG.contentEquals(pkg)) continue;
            if (clickByLabelDfs(root, "Check for updates", 0)) return;
            scrollRefresh(root, 0); // pull-to-refresh fallback
            return;
        }
    }

    private boolean scrollRefresh(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 40) return false;
        if (node.isScrollable()
                && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (scrollRefresh(node.getChild(i), depth + 1)) return true;
        }
        return false;
    }

    private boolean allAwake() {
        for (SleepingApp app : pending) {
            if (isAsleep(app.packageName)) return false;
        }
        return true;
    }

    private boolean isAsleep(String packageName) {
        try {
            // A tracked app that has fallen back into hibernation reports enabled == false
            // again (the same signal the scan uses to detect sleeping apps in the first place).
            return !getPackageManager().getApplicationInfo(packageName, 0).enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void finishFlow() {
        // Normal finish happens with pending/queue empty, but a mid-run stop can leave both
        // populated - everything not updated counts as still pending.
        ArrayList<String> notUpdated = new ArrayList<>(skippedNames);
        for (SleepingApp app : pending) notUpdated.add(app.appName);
        for (SleepingApp app : queue) notUpdated.add(app.appName);
        int pend = notUpdated.size();
        stopFlowInternal();
        Toast.makeText(this, "Batch update finished: " + updatedCount + " updated"
                + (pend > 0 ? ", " + pend + " still pending" : ""),
                Toast.LENGTH_LONG).show();
        // Hand the report to MainActivity, which shows it as a dialog.
        Intent report = new Intent(this, MainActivity.class);
        report.putStringArrayListExtra(EXTRA_REPORT_UPDATED, new ArrayList<>(updatedNames));
        report.putStringArrayListExtra(EXTRA_REPORT_NOT_UPDATED, notUpdated);
        bringDeepWakeToFront(report);
    }

    private void cancelFlow(boolean returnToApp) {
        if (!running && overlay == null) return;
        stopFlowInternal();
        if (returnToApp) bringDeepWakeToFront(new Intent(this, MainActivity.class));
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
        updatedNames.add(app.appName);
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

    private void bringDeepWakeToFront(Intent i) {
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
     *   1. Try to tap "Update all"/"Update" in Play Store's window. ACTION_CLICK is a
     *      programmatic click on the node, so it works even though our tint overlay sits on
     *      top, AND even if Play Store's window isn't flagged "active" (our overlay can steal
     *      that flag - which is exactly why an isActive()-gated approach never clicked).
     *      One "Update all" tap triggers every pending update, so we disarm after a hit.
     *   2. If nothing was tappable (a just-woken app is still on top, or Play Store hasn't
     *      surfaced yet), re-open Play Store's updates page - rate-limited - so the next tick
     *      can tap it. Re-opening when Play Store is already front is a harmless re-focus.
     */
    private void driveToPlayStoreAndClick() {
        if (!running || !autoClickArmed) return;

        if (clickUpdateInPlayStore()) {
            disarmAutoClick();
            return;
        }

        long now = System.currentTimeMillis();
        // Re-firing the deep link restarts the updates page's load from scratch. When Play
        // Store is already on screen and just slow to list updates, re-opening it every
        // couple of seconds means the check NEVER completes - the page sits on a spinner
        // (or a stale "up to date") indefinitely. So while Play Store is front, hold off
        // much longer; the short cooldown is only for shoving aside a woken app that is
        // still covering the screen.
        long cooldown = isPlayStoreShowing() ? REOPEN_SETTLED_COOLDOWN_MS : REOPEN_COOLDOWN_MS;
        if (now - lastReopenAttempt >= cooldown) {
            openPlayStoreUpdates();
            lastReopenAttempt = now;
        }
    }

    private boolean isPlayStoreShowing() {
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows == null) return false;
        for (AccessibilityWindowInfo w : windows) {
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null) continue;
            CharSequence pkg = root.getPackageName();
            if (pkg != null && PLAY_STORE_PKG.contentEquals(pkg)) return true;
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
        // Full-tree DFS rather than findAccessibilityNodeInfosByText(), which can miss
        // Compose-rendered elements - and on the "Manage apps & device" Overview screen
        // "Update all" is a Compose text link, not a classic Button, so the old shallow
        // lookup found nothing clickable and never tapped it.
        return clickByLabelDfs(root, label, 0);
    }

    private boolean clickByLabelDfs(AccessibilityNodeInfo node, String label, int depth) {
        if (node == null || depth > 40) return false;
        if (labelMatches(node, label) && clickSelfOrAncestor(node)) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            if (clickByLabelDfs(node.getChild(i), label, depth + 1)) return true;
        }
        return false;
    }

    private boolean labelMatches(AccessibilityNodeInfo node, String label) {
        // Exact (trimmed, case-insensitive) match on text OR content-description, so
        // "Update" matches the button but never "Updated on Jul 18" / "Updates available".
        CharSequence text = node.getText();
        if (text != null && text.toString().trim().equalsIgnoreCase(label)) return true;
        CharSequence desc = node.getContentDescription();
        return desc != null && desc.toString().trim().equalsIgnoreCase(label);
    }

    private boolean clickSelfOrAncestor(AccessibilityNodeInfo node) {
        // Prefer the nearest clickable+enabled ancestor (classic Button case)...
        AccessibilityNodeInfo target = node;
        for (int depth = 0; target != null && depth < 6; depth++) {
            if (target.isClickable() && target.isEnabled()) {
                if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            }
            target = target.getParent();
        }
        // ...otherwise click the labelled node directly. Compose links often carry the
        // click action on the text node itself without reporting isClickable().
        return node.isEnabled() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
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
                    // KEEP_SCREEN_ON: while the shade is up the screen can't time out and
                    // lock. A screen lock mid-run pauses Play Store's downloads and blocks
                    // our app/Play Store launches, which was leaving half the batch un-updated.
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
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
