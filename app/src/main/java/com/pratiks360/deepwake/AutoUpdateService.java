package com.pratiks360.deepwake;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Fully automated batch updating, SD-Maid style: while a batch run is active this service
 * draws a full-screen tint overlay that swallows all touches (so the user can't disturb
 * the flow - only the overlay's own Cancel button works), then drives the whole loop
 * itself. The selected apps are worked through in sequential batches of BATCH_SIZE: wake
 * the current batch's apps, drive Play Store to its DOWNLOADS screen (the deep link lands
 * on the Overview tab of "Manage apps and device", which is one click short of it), tap
 * "Check for updates" when nothing is listed, auto-click "Update all", then keep tapping the
 * per-row "Update" button of every app that appears afterwards - once anything is installing
 * "Update all" becomes "Cancel all", so those per-row buttons are the only way to start the
 * apps woken later in the batch. The batch is then monitored until each app's installed
 * version actually moves, switching between the woken apps and Play Store as needed.
 *
 * Only when the current batch completes does the next batch of apps get woken; waking 50 apps
 * at once just thrashes the device and re-hibernation undoes most of them before Play Store
 * gets there anyway. A batch is never abandoned while Play Store is visibly installing or
 * downloading (a big app can hold the batch for minutes without any version moving); it moves
 * on only after real inactivity, or at its hard time cap. On aggressive-hibernation devices a
 * woken app can still slip back to sleep mid-update (Play Store then stalls it), so while
 * waiting we periodically re-wake any pending app in the current batch that has gone back to
 * sleep and re-tap Update all - see maybeRewake. Each app also carries a bounded retry budget:
 * an app Play Store never lists is woken again, then checked directly against its Play Store
 * listing - if nothing newer exists it was only ever "outdated" by a stale scrape and counts
 * as done, otherwise it gets one last wake and is reported as failed.
 *
 * Progress is swept across the WHOLE run every tick, not just the live batch: Play Store's own
 * install queue ignores our batching and keeps working long after we've moved on, so apps
 * routinely land while a later batch is on screen. Anything a batch does leave behind is
 * watched to the end of the run (see drain) before the report is written, and that report is
 * persisted - the last five runs are browsable from MainActivity.
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
    private static final long CHECK_COOLDOWN_MS = 9000;  // min gap between "Check for updates" taps
    private static final int EMPTY_DOWNLOADS_TRIGGER = 4; // confirmed-empty polls -> re-wake batch
    // Per-app retry budget. An app that Play Store never lists is woken again; after
    // VERIFY_AFTER_ATTEMPTS fruitless wakes we ask Play Store directly what the latest
    // version is (see resolvePending), and MAX_WAKE_ATTEMPTS is the hard stop after which
    // the app is reported as failed rather than chased for the rest of the batch.
    private static final int VERIFY_AFTER_ATTEMPTS = 2;
    private static final int MAX_WAKE_ATTEMPTS = 5;
    // How long to give the app's own Play Store page to load before reading what it offers.
    private static final long DETAILS_SETTLE_MS = 2500;
    private static final int DETAILS_MAX_POLLS = 6;
    // ...but never judge an app while the batch is still visibly progressing: Play Store
    // installs one app at a time, and a batch-wide re-wake cycle bumps every pending app's
    // attempt count at once, so without this an app could be retired mid-download.
    private static final int RESOLVE_IDLE_TICKS = 12; // ~60s of no progress in the batch
    // A batch is NOT stalled while Play Store is visibly working on one of its apps, however
    // long that takes - a 600 MB download makes no "progress" by our measure for minutes.
    private static final int BUSY_GRACE_TICKS = 6;    // ~30s since the last busy row was seen
    // After the last batch, apps an earlier batch left mid-install are watched (not re-woken)
    // so they land in the report as updated rather than as failures.
    private static final int DRAIN_MAX_TICKS = 36;    // ~3 min hard cap on that wait
    private static final int DRAIN_IDLE_TICKS = 12;   // ...or ~60s of nothing happening
    private static final long UPDATE_ALL_COOLDOWN_MS = 5000;  // min gap between "Update all" taps
    private static final long PER_APP_CLICK_COOLDOWN_MS = 8000; // ...and per row "Update" taps
    private static final int MAX_NODES = 900;         // cap on one page-tree snapshot
    private static final String PLAY_STORE_PKG = "com.android.vending";

    // A run is unattended and can last an hour with the screen forced on (KEEP_SCREEN_ON),
    // so the shade dims the screen right down while it works. Not off: the status and the
    // Cancel button have to stay findable, and a fully black screen reads as a broken phone.
    private static final float DIM_BRIGHTNESS = 0.04f;
    private static final long UNDIM_MS = 10000;       // full brightness after a touch, for this long

    // Labels used to drive Play Store's UI. The Downloads screen is the only page with a
    // real "Update all"; the Overview page of "Manage apps and device" carries a row that
    // navigates INTO Downloads, which is where the deep link tends to land instead.
    // Once anything is installing, "Update all" turns into "Cancel all" - which is why the
    // per-row "Update" buttons (see clickPerAppUpdates) are the only way to start the apps
    // that Play Store lists later, as each newly woken app appears.
    private static final String UPDATE_ALL_LABEL = "Update all";
    private static final String UPDATE_LABEL = "Update";
    private static final String CHECK_LABEL = "Check for updates";
    // An app's own Play Store page has settled once one of these is on it. "Update" there is
    // Play Store's own answer to whether this device is being offered the update at all.
    private static final String[] DETAILS_SETTLED_LABELS = {UPDATE_LABEL, "Open", "Uninstall",
            "Install", "Play"};
    private static final String[] DOWNLOADS_MARKERS =
            {"Downloads", CHECK_LABEL, UPDATE_ALL_LABEL, "Cancel all", "You're ready"};
    private static final String[] DOWNLOADS_ENTRY_LABELS =
            {"Updates available", "All apps up to date", "See details"};
    // Row status text that means Play Store is actively working on that app. Matched as a
    // lowercase prefix, so "Installing...", "Pending..." and "Downloading 12 MB of 45 MB"
    // all count.
    // Deliberately only ACTIVE work - "Pending..." rows can sit there indefinitely behind a
    // download that will never start (no Wi-Fi, no storage), and treating those as busy would
    // hold a batch open for nothing.
    private static final String[] BUSY_MARKERS = {"installing", "downloading", "verifying"};

    // Extra on the MainActivity relaunch intent: the id of the report just written to the DB.
    public static final String EXTRA_REPORT_ID = "com.pratiks360.deepwake.REPORT_ID";

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
    private WindowManager.LayoutParams overlayParams; // kept, so brightness can be changed live
    private final Runnable redim = () -> setOverlayBrightness(DIM_BRIGHTNESS);

    // queue holds the selected apps not yet attempted; pending is the CURRENT batch's
    // not-yet-updated apps (at most BATCH_SIZE). All the wake/monitor/re-wake machinery
    // below operates on pending only - the queue feeds it one batch at a time.
    private final List<SleepingApp> queue = new ArrayList<>();
    private final List<SleepingApp> pending = new ArrayList<>();
    // Apps a stalled/capped batch left behind. Play Store usually IS still installing them
    // (it installs one app at a time, from a queue that outlives our batch), so instead of
    // writing them off they keep being swept for completion until the run ends.
    private final List<SleepingApp> deferred = new ArrayList<>();
    private final List<BatchReport.Item> reportItems = new ArrayList<>();
    // Per-package wake attempts and which packages have already been re-checked against
    // Play Store, so each app is chased a bounded number of times and verified only once.
    private final Map<String, Integer> wakeAttempts = new HashMap<>();
    private final Set<String> verified = new HashSet<>();
    // Package whose Play Store page is being read right now, or null. Everything else in the
    // flow holds off while it is set - the drive loop expects the Downloads screen, and a
    // details page is not it.
    private String inspecting;
    // Last tap per Play Store row label, so a row's "Update" isn't re-tapped every poll
    // while the UI catches up.
    private final Map<String, Long> lastRowClick = new HashMap<>();
    // Why an app didn't make it, keyed by package - recorded at the moment we learn it
    // (the batch timed out, Play Store never listed it, the version check couldn't reach
    // the store) and read back when the report is written, so a report says what went
    // wrong rather than just that something did.
    private final Map<String, String> failureReasons = new HashMap<>();
    private int totalSelected;
    private int batchNumber;      // 1-based, for the overlay status
    private int updatedCount;
    private int lastProgressTick; // tick (within the current batch) of the last update
    private int currentTick;      // tick the monitor/drain loop is on, for busy bookkeeping
    private int busyTick = -1;    // last tick Play Store looked busy for our apps (-1 = never)
    private boolean running;
    private boolean autoClickArmed;
    private long lastReopenAttempt;
    private long lastCheckClick;     // last "Check for updates" tap, for its cooldown
    private long lastUpdateAllClick; // last "Update all" tap, for its cooldown
    private int emptyDownloadsStreak; // polls seeing an empty Downloads AFTER a completed check
    private int wakeCyclesThisBatch;  // wake-all + re-check rounds run for the current batch

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
        batchNumber = 0;
        busyTick = -1;
        reportItems.clear();
        wakeAttempts.clear();
        verified.clear();
        inspecting = null;
        lastRowClick.clear();
        failureReasons.clear();
        deferred.clear();
        queue.clear();
        queue.addAll(apps);
        totalSelected = queue.size();
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
            // Queue exhausted. Anything an earlier batch left mid-install gets watched a
            // while longer before the report is written; otherwise finish now.
            if (deferred.isEmpty()) {
                finishFlow();
            } else {
                lastProgressTick = 0;
                setOverlayStatus("Finishing " + deferred.size() + " app(s) still installing...");
                armAutoClick();
                handler.postDelayed(() -> drain(0), VERIFY_INTERVAL_MS);
            }
            return;
        }
        batchNumber++;
        lastProgressTick = 0;
        busyTick = -1;    // tick counters restart per batch, so the busy mark must too
        emptyDownloadsStreak = 0;
        wakeCyclesThisBatch = 0;
        lastCheckClick = 0;   // the new batch may need an immediate re-check
        disarmAutoClick(); // no Play Store taps while the new batch's apps are being woken
        handler.post(() -> wakeAll(0));
    }

    /** Wake the current batch's apps (staggered), then hand off to Play Store + monitor. */
    private void wakeAll(int i) {
        if (!running) return;
        if (i >= pending.size()) {
            handler.postDelayed(() -> {
                if (!running) return;
                openPlayStoreDownloads();
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
     * Loop over the CURRENT batch: sweep the whole run for completed updates, re-wake any app
     * that slipped back to sleep, keep Play Store tapping Update. When the batch empties it
     * rolls straight into the next one. A batch is left behind only after real inactivity -
     * never while Play Store is installing one of its apps - or at its hard cap, so one stuck
     * app can't wedge the rest of the queue; what it leaves behind is handed to `deferred`
     * and still counted if it lands later, and the overlay always dismisses itself instead of
     * sitting on Play Store forever.
     * resolvePending additionally retires individual apps that have burnt their retry
     * budget, so a single never-listed app is settled on its own terms (verified against
     * Play Store, then updated-or-failed) rather than dragging the batch to the timeout.
     */
    private void monitor(int tick) {
        if (!running) return;
        currentTick = tick;

        if (sweepCompleted()) lastProgressTick = tick;

        // Retire apps that have used up their retry budget, so the batch shrinks instead of
        // one never-listed app holding the whole run to the stall timeout.
        resolvePending(tick);

        if (pending.isEmpty()) {
            startNextBatch();
            return;
        }

        if (inspecting != null) {
            // An app's store page is open and being read. Waking apps, restarting Play Store
            // or calling the batch stalled underneath that would only interrupt the answer.
            handler.postDelayed(() -> monitor(tick + 1), VERIFY_INTERVAL_MS);
            return;
        }

        setOverlayStatus(batchLabel() + updatedCount + "/" + totalSelected + " updated, "
                + pending.size() + " in this batch"
                + (deferred.isEmpty() ? "..." : " (" + deferred.size() + " finishing)..."));

        // Skip this batch if it hit its hard cap, or if nothing has updated for a while AND
        // nothing is still asleep (so re-waking has nothing left to try - Play Store simply
        // has no more updates to give, e.g. false-positive "outdated" entries). Never give
        // up before at least one full wake-all + re-check cycle has run, though: that cycle
        // is the only thing that makes a re-slept app visible to Play Store's check, and
        // abandoning the batch first is what left apps behind as "Not updated".
        //
        // And never while Play Store is visibly installing/downloading one of this batch's
        // apps: a big app can sit at "Installing..." well past STALL_TICKS without any
        // version moving, and abandoning the batch there is exactly what was marching the
        // run through batches with nothing counted as updated.
        boolean busy = isBusy(tick);
        boolean stalled = !busy && (tick - lastProgressTick) >= STALL_TICKS && allAwake()
                && wakeCyclesThisBatch > 0;
        if (tick + 1 >= BATCH_MAX_TICKS || stalled) {
            String reason = stalled
                    ? "Play Store made no progress on it for "
                            + minutes(STALL_TICKS) + " min"
                    : "its batch hit the " + minutes(BATCH_MAX_TICKS) + " min limit";
            for (SleepingApp app : pending) failureReasons.put(app.packageName, reason);
            deferred.addAll(pending);   // keep watching them; the run reports on them at the end
            pending.clear();
            startNextBatch();
            return;
        }

        if (busy) {
            // Play Store is working through this batch - leave it alone. The auto-click poll
            // stays armed so each row's own "Update" is still tapped as it appears; waking
            // apps or restarting Play Store here would only interrupt a live install.
            handler.postDelayed(() -> monitor(tick + 1), VERIFY_INTERVAL_MS);
            return;
        }

        if (shouldRestartPlayStore(tick)) {
            restartPlayStore();
        } else if (emptyDownloadsStreak >= EMPTY_DOWNLOADS_TRIGGER) {
            // Play Store checked and genuinely listed nothing. A sleeping app is invisible
            // to that check, so wake the whole batch and drive it back to Downloads for a
            // fresh check with the apps actually awake.
            emptyDownloadsStreak = 0;
            setOverlayStatus(batchLabel() + "No updates listed - re-waking "
                    + pending.size() + " app(s)...");
            wakePendingThenReturnToDownloads();
        } else {
            maybeRewake(tick);
        }
        handler.postDelayed(() -> monitor(tick + 1), VERIFY_INTERVAL_MS);
    }

    /**
     * Sweeps EVERY app in the run - the current batch, the apps a stalled batch left behind
     * and the ones still queued - for an install that has landed, and counts each one.
     *
     * Sweeping all three is the point: Play Store's own queue doesn't respect our batching
     * (an "Update all" tap starts everything it has listed, and installs keep running long
     * after we've moved on), so an app frequently finishes while some later batch is on
     * screen. Only ever checking the current batch is what pinned the counter at "0/22"
     * while Play Store was visibly installing the apps.
     */
    private boolean sweepCompleted() {
        boolean progressed = sweepList(pending);
        progressed |= sweepList(deferred);
        progressed |= sweepList(queue);
        return progressed;
    }

    private boolean sweepList(List<SleepingApp> apps) {
        boolean progressed = false;
        Iterator<SleepingApp> it = apps.iterator();
        while (it.hasNext()) {
            SleepingApp app = it.next();
            if (isUpdated(app)) {
                markUpdated(app); // counts it + drops it from the tracked list
                it.remove();
                progressed = true;
            }
        }
        return progressed;
    }

    /** True while Play Store was recently seen installing/downloading one of our apps. */
    private boolean isBusy(int tick) {
        return busyTick >= 0 && tick - busyTick <= BUSY_GRACE_TICKS;
    }

    /**
     * The tail of a run: every batch has been through, but Play Store may still be installing
     * apps that earlier batches handed off. Nothing is woken or re-driven here - the poll
     * keeps tapping any per-row "Update" that shows up, and this just watches the installs
     * land so they're reported as updated instead of as failures.
     */
    private void drain(int tick) {
        if (!running) return;
        currentTick = tick;
        if (sweepCompleted()) lastProgressTick = tick;

        boolean idle = !isBusy(tick) && (tick - lastProgressTick) >= DRAIN_IDLE_TICKS;
        if (deferred.isEmpty() || idle || tick + 1 >= DRAIN_MAX_TICKS) {
            finishFlow();
            return;
        }
        setOverlayStatus(updatedCount + "/" + totalSelected + " updated - finishing "
                + deferred.size() + " app(s) still installing...");
        handler.postDelayed(() -> drain(tick + 1), VERIFY_INTERVAL_MS);
    }

    /**
     * Enforces the per-app retry budget. When Play Store lists 3 of a batch's 4 apps, the
     * missing one gets woken again by the normal cycles; this decides when to stop chasing:
     *
     *   after VERIFY_AFTER_ATTEMPTS fruitless wakes -> open the app's own Play Store page and
     *       read what it offers (see inspectOnPlayStore). No Update button there means this
     *       device is not being offered the update at all, so the app stops being chased.
     *   at MAX_WAKE_ATTEMPTS with Play Store still offering it -> report it as failed and
     *       drop it, so the batch moves on.
     *
     * Only one app is inspected at a time - it takes the screen - so the rest of a batch's
     * candidates come round on later ticks.
     */
    private void resolvePending(int tick) {
        if (!running) return;
        if (tick - lastProgressTick < RESOLVE_IDLE_TICKS) return; // batch still moving
        if (isBusy(tick)) return; // Play Store is installing - nothing here has failed yet
        List<SleepingApp> toVerify = new ArrayList<>();
        Iterator<SleepingApp> it = pending.iterator();
        while (it.hasNext()) {
            SleepingApp app = it.next();
            int attempts = attemptsFor(app.packageName);
            if (attempts < VERIFY_AFTER_ATTEMPTS) continue;
            if (!verified.contains(app.packageName)) {
                toVerify.add(app); // resolved on a later tick, once the page has been read
                continue;
            }
            // Checked, and Play Store does offer it (or the page wouldn't load). Give it the
            // rest of its budget, then stop.
            if (attempts >= MAX_WAKE_ATTEMPTS) {
                markFailed(app, "Play Store offered it but never started - woken "
                        + attempts + " times");
                it.remove();
            }
        }
        if (!toVerify.isEmpty()) inspectOnPlayStore(toVerify.get(0));
    }

    /**
     * Asks Play Store itself, on this device, whether it has an update for the app - by
     * opening the app's own store page and reading the button on it.
     *
     * This replaces a re-fetch of the same scraped web listing the scan uses. That check
     * could only ever confirm what the scan already believed, which is exactly the wrong
     * answer for the case it was meant to catch: the published version genuinely IS newer,
     * but Play Store isn't offering it to THIS device (staged rollout, device or ABI
     * filtering, or an OEM preload the store won't take over). Reports came back full of
     * apps chased to their retry limit for an update that was never on offer here.
     *
     * The page's own button is the ground truth, and it's actionable: an "Update" there is
     * tapped on the spot - the surest place to start the install, since it doesn't depend on
     * the app being listed on Downloads at all.
     */
    private void inspectOnPlayStore(SleepingApp app) {
        if (inspecting != null) return; // one at a time; the rest come round on later ticks
        inspecting = app.packageName;
        disarmAutoClick(); // no Downloads-page driving while we're on a details page
        setOverlayStatus(batchLabel() + "Asking Play Store about " + app.appName + "...");
        Intent details = new Intent(Intent.ACTION_VIEW,
                Uri.parse("market://details?id=" + app.packageName))
                .setPackage(PLAY_STORE_PKG)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!tryStart(details)) {
            finishInspect(app, false, false);
            return;
        }
        handler.postDelayed(() -> readDetailsPage(app, 0), DETAILS_SETTLE_MS);
    }

    private void readDetailsPage(SleepingApp app, int poll) {
        if (!running) return;
        AccessibilityNodeInfo root = findPlayStoreRoot();
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        if (root != null) collectNodes(root, nodes, 0);

        boolean offersUpdate = hasLabel(nodes, UPDATE_LABEL);
        boolean settled = offersUpdate || hasAnyLabel(nodes, DETAILS_SETTLED_LABELS);
        if (!settled && poll + 1 < DETAILS_MAX_POLLS) {
            handler.postDelayed(() -> readDetailsPage(app, poll + 1), POLL_INTERVAL_MS);
            return;
        }
        // An "Update" on this page is worth pressing right here.
        if (offersUpdate && root != null) clickByLabelDfs(root, UPDATE_LABEL, 0);
        finishInspect(app, settled, offersUpdate);
    }

    /**
     * @param settled      the page loaded far enough to be believed
     * @param offersUpdate Play Store showed an Update button for this app on this device
     */
    private void finishInspect(SleepingApp app, boolean settled, boolean offersUpdate) {
        inspecting = null;
        if (!running) return;
        verified.add(app.packageName);

        if (!settled) {
            // Couldn't read the page - the retry budget still applies, but say so, rather
            // than blaming Play Store for not offering something we never managed to ask about.
            failureReasons.put(app.packageName, "its Play Store page wouldn't load");
        } else if (offersUpdate) {
            // Play Store does have it for this device, and the install has just been started
            // from the page itself. Give the app its budget back so the batch keeps watching
            // it rather than retiring it while it downloads.
            wakeAttempts.put(app.packageName, 0);
            failureReasons.remove(app.packageName);
            busyTick = currentTick; // treat this as live work, not a stalled batch
            setOverlayStatus(batchLabel() + "Updating " + app.appName + " from its store page...");
        } else {
            // Play Store has nothing for this device. The app was only ever "outdated"
            // against the published listing, so stop chasing it - but it stays in the app
            // list and isn't counted as an update this run performed. It's recorded in the
            // settled bucket rather than as a failure: nothing failed, there was simply
            // nothing on offer.
            if (pending.remove(app)) {
                reportItems.add(new BatchReport.Item(app.appName, app.packageName,
                        BatchReport.STATUS_ALREADY_CURRENT,
                        "Play Store offers no update for this device"));
            }
        }

        openPlayStoreDownloads();
        armAutoClick();
    }

    private boolean hasLabel(List<AccessibilityNodeInfo> nodes, String label) {
        for (AccessibilityNodeInfo node : nodes) {
            String found = labelOf(node);
            if (found != null && found.equalsIgnoreCase(label)) return true;
        }
        return false;
    }

    private boolean hasAnyLabel(List<AccessibilityNodeInfo> nodes, String[] labels) {
        for (String label : labels) {
            if (hasLabel(nodes, label)) return true;
        }
        return false;
    }

    private int attemptsFor(String packageName) {
        Integer n = wakeAttempts.get(packageName);
        return n == null ? 0 : n;
    }

    /**
     * Out of retries with a genuinely newer version still on the store. The reason recorded
     * while chasing the app (if any) wins over the caller's - it's the more specific one.
     */
    private void markFailed(SleepingApp app, String reason) {
        String recorded = failureReasons.get(app.packageName);
        reportItems.add(new BatchReport.Item(app.appName, app.packageName,
                BatchReport.STATUS_FAILED, recorded != null ? recorded : reason));
    }

    /** Tick counts read as minutes, for reasons a person has to make sense of. */
    private int minutes(int ticks) {
        return Math.max(1, Math.round(ticks * VERIFY_INTERVAL_MS / 60000f));
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
            wakePendingThenReturnToDownloads();
        }, 800);
    }

    /**
     * Wake every app still pending in this batch, then drive back to Play Store's Downloads
     * screen and force a fresh check. Play Store only lists an app as updatable while that
     * app is awake, so the check has to run with the whole batch awake - waking them first
     * and re-checking after is the sequence that actually surfaces them.
     */
    private void wakePendingThenReturnToDownloads() {
        disarmAutoClick(); // no Play Store taps while we flip through the apps
        wakeCyclesThisBatch++;
        wakePendingStep(pendingWorthWaking(), 0);
    }

    private void wakePendingStep(List<SleepingApp> toWake, int i) {
        if (!running) return;
        if (i >= toWake.size()) {
            handler.postDelayed(() -> {
                if (!running) return;
                openPlayStoreDownloads();
                // Let the page settle, then force the re-check; the poll takes over from
                // there and taps "Update all" as soon as the list populates.
                lastCheckClick = 0;
                emptyDownloadsStreak = 0;
                handler.postDelayed(this::refreshPlayStore, 2500);
                armAutoClick();
            }, SETTLE_MS);
            return;
        }
        launchApp(toWake.get(i).packageName);
        handler.postDelayed(() -> wakePendingStep(toWake, i + 1), STAGGER_MS);
    }

    /**
     * "Batch 4 of 6" - recomputed rather than fixed at start, because apps still queued can
     * finish on their own (Play Store's "Update all" starts everything it has listed), which
     * shortens the run.
     */
    private String batchLabel() {
        int total = batchNumber + (queue.size() + BATCH_SIZE - 1) / BATCH_SIZE;
        return total > 1 ? "Batch " + batchNumber + " of " + total + "\n" : "";
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
            if (isAsleep(app.packageName) && worthWaking(app)) asleep.add(app);
        }
        if (asleep.isEmpty()) return; // nothing re-slept; the auto-click poll carries on
        disarmAutoClick(); // don't tap Play Store while we're flipping through the apps
        rewakeStep(asleep, 0);
    }

    /**
     * Whether this app is still worth waking. MAX_WAKE_ATTEMPTS used to gate only the moment
     * an app was written off, not the waking itself - and because retirement waits for the
     * batch to go quiet (see resolvePending), reports were coming back saying apps had been
     * "woken 14 times" against a limit of 3. Every wake cycle re-launched every pending app
     * regardless. Now the budget stops the launches too, so an app Play Store isn't going to
     * offer stops costing screen flips and time the moment its budget is gone.
     */
    private boolean worthWaking(SleepingApp app) {
        return attemptsFor(app.packageName) < MAX_WAKE_ATTEMPTS;
    }

    private List<SleepingApp> pendingWorthWaking() {
        List<SleepingApp> out = new ArrayList<>();
        for (SleepingApp app : pending) {
            if (worthWaking(app)) out.add(app);
        }
        return out;
    }

    private void rewakeStep(List<SleepingApp> asleep, int i) {
        if (!running) return;
        if (i >= asleep.size()) {
            // All re-woken - return to Play Store, force it to RE-CHECK for updates so the
            // apps that slept (and dropped off the cached "Available updates" list) show up
            // again, then re-tap Update all.
            openPlayStoreDownloads();
            lastCheckClick = 0;
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
     * Taps the Downloads screen's "Check for updates" button, falling back to
     * pull-to-refresh (scroll the list up) on builds that don't show one.
     */
    private void refreshPlayStore() {
        if (!running) return;
        AccessibilityNodeInfo root = findPlayStoreRoot();
        if (root == null) return;
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, nodes, 0);
        if (!onDownloadsPage(nodes)) {
            // Still on the Overview page - step into Downloads and re-check shortly after.
            for (String entry : DOWNLOADS_ENTRY_LABELS) {
                if (clickByLabelDfs(root, entry, 0)) {
                    handler.postDelayed(this::refreshPlayStore, 1500);
                    return;
                }
            }
            return;
        }
        if (clickByLabelDfs(root, CHECK_LABEL, 0)) {
            lastCheckClick = System.currentTimeMillis();
            return;
        }
        scrollRefresh(root, 0); // pull-to-refresh fallback
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

    /**
     * True when nothing is left that waking could still help. An app past its wake budget
     * counts as awake here whether it is or not: nobody is going to launch it again, so its
     * sleep state can no longer explain why the batch isn't moving - and leaving it out of
     * this answer used to hold the batch open to its full 15-minute cap.
     */
    private boolean allAwake() {
        for (SleepingApp app : pending) {
            if (worthWaking(app) && isAsleep(app.packageName)) return false;
        }
        return true;
    }

    private boolean isAsleep(String packageName) {
        return Packages.isAsleep(getPackageManager(), packageName);
    }

    private void finishFlow() {
        finishFlow(null);
    }

    /**
     * @param cancelReason set when the run was stopped early, so every app still outstanding
     *                     says so instead of carrying whatever reason it had at the time.
     */
    private void finishFlow(String cancelReason) {
        // Normal finish happens with every list empty, but the drain's time cap - or a mid-run
        // stop - can leave apps behind; those are recorded as still pending, not as failures,
        // since Play Store may well finish them minutes later.
        for (SleepingApp app : deferred) {
            addPending(app, cancelReason != null ? cancelReason
                    : reasonFor(app, "still installing when the run ended"));
        }
        for (SleepingApp app : pending) {
            addPending(app, cancelReason != null ? cancelReason
                    : reasonFor(app, "the run ended while its batch was still going"));
        }
        for (SleepingApp app : queue) {
            addPending(app, cancelReason != null ? cancelReason
                    : "the run ended before its batch started");
        }
        int outstanding = 0;
        for (BatchReport.Item item : reportItems) {
            if (!BatchReport.STATUS_UPDATED.equals(item.status)
                    && !BatchReport.STATUS_ALREADY_CURRENT.equals(item.status)) {
                outstanding++;
            }
        }
        stopFlowInternal();
        Toast.makeText(this, (cancelReason == null ? "Batch update finished: " : "Cancelled: ")
                + updatedCount + " updated"
                + (outstanding > 0 ? ", " + outstanding + " still pending" : ""),
                Toast.LENGTH_LONG).show();

        // The report is persisted (last 5 runs are kept) and MainActivity is handed its id
        // rather than the contents, so the same report can be reopened later from history.
        long reportId = AppRepository.saveReport(this, reportItems, totalSelected, updatedCount);
        Intent report = new Intent(this, MainActivity.class);
        report.putExtra(EXTRA_REPORT_ID, reportId);
        bringDeepWakeToFront(report);
    }

    private void addPending(SleepingApp app, String detail) {
        reportItems.add(new BatchReport.Item(app.appName, app.packageName,
                BatchReport.STATUS_PENDING, detail));
    }

    private String reasonFor(SleepingApp app, String fallback) {
        String recorded = failureReasons.get(app.packageName);
        return recorded != null ? recorded : fallback;
    }

    /**
     * The user hit Cancel. That's still a run worth recording - it's the case where knowing
     * what had and hadn't happened matters most - so it lands in the history like any other,
     * with everything outstanding marked as cancelled.
     */
    private void cancelFlow(boolean returnToApp) {
        if (!running && overlay == null) return;
        if (running && returnToApp) {
            finishFlow("run cancelled");
            return;
        }
        stopFlowInternal();
        if (returnToApp) bringDeepWakeToFront(new Intent(this, MainActivity.class));
    }

    private void stopFlowInternal() {
        running = false;
        autoClickArmed = false;
        inspecting = null;
        handler.removeCallbacksAndMessages(null);
        hideOverlay();
    }

    // ---------------------------------------------------------------- verification

    /**
     * Has this app's update landed? The installed version simply MOVING off the version
     * recorded at scan time is the primary signal - Play Store is the only thing updating
     * these apps, so a changed version means it did.
     *
     * The old test demanded installed.equals(latestVersion), an exact string match against a
     * version scraped off the store page. Those two strings routinely differ for the same
     * build ("8.1.2" on the listing vs "8.1.2.4-release" in the package), so genuinely
     * updated apps kept failing the check and the run reported nothing as updated even while
     * Play Store showed them installing. The scraped version is now only a fallback, used
     * when there is no usable baseline to compare against.
     */
    private boolean isUpdated(SleepingApp app) {
        String installed = getInstalledVersion(app.packageName);
        if (installed == null) return false; // uninstalled mid-run - not something we did
        if (app.currentVersion != null && !app.currentVersion.isEmpty()) {
            return !installed.equals(app.currentVersion);
        }
        String latest = app.latestVersion;
        return PlayStoreVersionFetcher.isUsableVersion(latest)
                && !PlayStoreVersionFetcher.isNewerVersion(latest, installed);
    }

    /**
     * Counts the app as done and stops tracking it. An app whose version never moved was
     * already on the latest build - the stored "latest" came from a stale scrape - so it's
     * recorded as such rather than claimed as an update this run performed.
     */
    private void markUpdated(SleepingApp app) {
        String installed = getInstalledVersion(app.packageName);
        boolean moved = installed != null && app.currentVersion != null
                && !installed.equals(app.currentVersion);
        updatedCount++;
        if (moved) {
            // Both versions, so the report says what actually changed, not just where it
            // landed: "5.5 → 5.6".
            String from = app.currentVersion.isEmpty() ? "?" : app.currentVersion;
            reportItems.add(new BatchReport.Item(app.appName, app.packageName,
                    BatchReport.STATUS_UPDATED, from + " → " + installed));
            app.currentVersion = installed;
        } else {
            reportItems.add(new BatchReport.Item(app.appName, app.packageName,
                    BatchReport.STATUS_ALREADY_CURRENT,
                    installed == null ? "already up to date" : "already on " + installed));
        }
        forget(app);
    }

    /** Drop the app from the tracked list - it no longer needs updating. */
    private void forget(SleepingApp app) {
        AppRepository.removeApp(this, app.packageName);
    }

    private String getInstalledVersion(String packageName) {
        return Packages.installedVersion(getPackageManager(), packageName);
    }

    // ---------------------------------------------------------------- activity starts

    /**
     * Wakes an app by launching it. Every call counts against that package's retry budget -
     * this is the only path that wakes apps (Play Store goes through tryStart), so counting
     * here catches the initial wake, the batch-wide re-wake cycles and the re-slept re-wakes
     * alike. resolvePending acts on the count.
     */
    private void launchApp(String packageName) {
        wakeAttempts.put(packageName, attemptsFor(packageName) + 1);
        PackageManager pm = getPackageManager();
        Intent intent = Packages.launchIntent(pm, packageName);
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(intent);
            } catch (Exception e) {
                Log.w(TAG, "launch failed for " + packageName + ": " + e.getMessage());
            }
        }
    }

    /**
     * Brings up Play Store's updates UI. On current builds this deep link lands on the
     * OVERVIEW tab of "Manage apps and device", not the Downloads screen with "Update all" -
     * driveToPlayStoreAndClick finishes the journey by clicking through to Downloads.
     */
    private void openPlayStoreDownloads() {
        // Package pinned so this internal action resolves reliably.
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
     * The heart of the batch loop's reliability - a small state machine run on every tick
     * (poll + accessibility event), which always works toward the same place: Play Store's
     * DOWNLOADS screen with "Update all" tapped.
     *   1. Play Store not on screen (a just-woken app still covers it) -> reopen it.
     *   2. On some OTHER Play Store page - the deep link usually lands on the Overview tab
     *      of "Manage apps and device", which has no real "Update all" - so click the row
     *      that navigates into Downloads rather than tapping anything there.
     *   3. On Downloads with updates listed -> tap "Update all". ACTION_CLICK is a
     *      programmatic click on the node, so it works even though our tint overlay sits on
     *      top, AND even if Play Store's window isn't flagged "active" (our overlay can steal
     *      that flag - which is exactly why an isActive()-gated approach never clicked).
     *   4. On Downloads mid-run -> "Update all" has become "Cancel all", and every app woken
     *      since then is listed with its own "Update" button. Tap those, one per pass. This
     *      is what actually gets a batch's later apps moving; the poll deliberately stays
     *      armed for the whole batch instead of stopping after the first "Update all".
     *   5. On Downloads with nothing listed ("You're ready") -> tap "Check for updates" and
     *      let it finish. If it still lists nothing, count that; once the count trips,
     *      monitor() re-wakes the whole batch and comes back here (see the empty-Downloads
     *      handling in monitor) - a sleeping app is invisible to Play Store's check, so the
     *      apps must be awake at the moment the check runs.
     */
    private void driveToPlayStoreAndClick() {
        if (!running || !autoClickArmed) return;

        AccessibilityNodeInfo root = findPlayStoreRoot();
        if (root == null) {
            maybeReopen(false);
            return;
        }

        // One tree snapshot per pass, shared by every check below - the page detection,
        // the busy scan and the button hunt used to walk the tree separately each time.
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, nodes, 0);

        if (!onDownloadsPage(nodes)) {
            // Navigate into Downloads from wherever we landed (usually the Overview tab).
            for (String entry : DOWNLOADS_ENTRY_LABELS) {
                if (clickByLabelDfs(root, entry, 0)) return;
            }
            maybeReopen(true);
            return;
        }

        // Tell the monitor whether Play Store is actually working, so it waits out a long
        // install instead of calling the batch stalled and moving on.
        if (pageBusy(nodes)) {
            busyTick = currentTick;
            emptyDownloadsStreak = 0;
        }

        long now = System.currentTimeMillis();

        // "Update all" starts everything Play Store has listed right now. It's only present
        // while nothing is installing - the moment one download starts it becomes "Cancel
        // all", which our exact-match lookup will never hit (and must never click).
        if (now - lastUpdateAllClick >= UPDATE_ALL_COOLDOWN_MS
                && clickByLabelDfs(root, UPDATE_ALL_LABEL, 0)) {
            lastUpdateAllClick = now;
            emptyDownloadsStreak = 0;
            return;
        }

        // ...which leaves the apps Play Store lists AFTER that tap: each app woken later
        // appears with its own "Update" button next to it while the rest are already
        // installing. Those rows are the only way to start them, so the poll stays armed and
        // taps them as they show up (it used to disarm after one "Update all" and never come
        // back, which is why the later apps of a batch were never started).
        if (clickPerAppUpdates(nodes, now)) {
            emptyDownloadsStreak = 0;
            return;
        }

        // Downloads has no button left to press. If installs are running that's simply the
        // waiting state - don't re-check (a check mid-install just churns the list) and don't
        // start counting empty polls toward a re-wake.
        if (isBusy(currentTick)) return;

        // On Downloads, nothing to update. Ask Play Store to re-check - rate-limited, since
        // re-tapping mid-check just restarts it and the check can never settle.
        if (now - lastCheckClick >= CHECK_COOLDOWN_MS) {
            if (clickByLabelDfs(root, CHECK_LABEL, 0)) {
                lastCheckClick = now;
                setOverlayStatus(batchLabel() + "Asking Play Store to check for updates...");
            } else {
                // Downloads is empty and offers no check button either - nothing more to
                // try on this screen, so let the re-wake cycle take over.
                emptyDownloadsStreak++;
            }
        } else if (now - lastCheckClick >= CHECK_COOLDOWN_MS / 2) {
            // The check we asked for has had time to finish and Downloads is still empty -
            // that is the signal the batch's apps need re-waking before the next check.
            emptyDownloadsStreak++;
        }
    }

    /** Flattens the page into a node list once, so a pass doesn't re-walk it per lookup. */
    private void collectNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out, int depth) {
        if (node == null || depth > 40 || out.size() >= MAX_NODES) return;
        out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            collectNodes(node.getChild(i), out, depth + 1);
        }
    }

    /** A node's visible label: its text, or its content description when it has no text. */
    private String labelOf(AccessibilityNodeInfo node) {
        CharSequence text = node.getText();
        if (text != null && text.toString().trim().length() > 0) return text.toString().trim();
        CharSequence desc = node.getContentDescription();
        if (desc != null && desc.toString().trim().length() > 0) return desc.toString().trim();
        return null;
    }

    /** True when the visible Play Store page is the Downloads screen. */
    private boolean onDownloadsPage(List<AccessibilityNodeInfo> nodes) {
        for (AccessibilityNodeInfo node : nodes) {
            String label = labelOf(node);
            if (label == null) continue;
            for (String marker : DOWNLOADS_MARKERS) {
                if (label.equalsIgnoreCase(marker)) return true;
            }
        }
        return false;
    }

    /**
     * True when any row on Downloads is actively installing or downloading.
     *
     * Not restricted to this batch's apps on purpose: Play Store queues everything it has
     * listed (an "Update all" tap starts far more than our four) and installs them strictly
     * one at a time, so a row we didn't ask for is still the reason ours haven't landed yet.
     * Waiting it out is right either way - and the batch's own 15-minute cap stops this from
     * becoming an indefinite hold.
     */
    private boolean pageBusy(List<AccessibilityNodeInfo> nodes) {
        for (AccessibilityNodeInfo node : nodes) {
            String label = labelOf(node);
            if (label == null) continue;
            String lower = label.toLowerCase(Locale.US);
            for (String marker : BUSY_MARKERS) {
                if (lower.startsWith(marker) || lower.contains(" " + marker)) return true;
            }
        }
        return false;
    }

    /**
     * Taps one row's own "Update" button per pass. Exact-match on the label, so it can never
     * hit "Update all" (handled separately), "Cancel all" or a row's "Updated on ..." text.
     *
     * One per pass because the list re-lays out as soon as a row starts installing - clicking
     * several stale nodes from the same snapshot mostly hits moved rows. The per-row cooldown
     * (keyed on the app name the button sits next to) stops a row being re-tapped every 1.2s
     * while Play Store catches up.
     */
    private boolean clickPerAppUpdates(List<AccessibilityNodeInfo> nodes, long now) {
        for (AccessibilityNodeInfo node : nodes) {
            String label = labelOf(node);
            if (label == null || !label.equalsIgnoreCase(UPDATE_LABEL)) continue;
            String row = rowTitleFor(node);
            String key = row == null ? "row@" + nodes.indexOf(node) : row;
            Long last = lastRowClick.get(key);
            if (last != null && now - last < PER_APP_CLICK_COOLDOWN_MS) continue;
            if (clickSelfOrAncestor(node)) {
                lastRowClick.put(key, now);
                setOverlayStatus(batchLabel() + "Updating " + (row == null ? "an app" : row) + "...");
                return true;
            }
        }
        return false;
    }

    /** The app name a per-row button belongs to - the row's longest non-status label. */
    private String rowTitleFor(AccessibilityNodeInfo button) {
        AccessibilityNodeInfo container = button;
        for (int i = 0; i < 3; i++) {
            AccessibilityNodeInfo parent = container.getParent();
            if (parent == null) break;
            container = parent;
            String title = longestLabel(container, 0, null);
            if (title != null && title.length() > 3) return title;
        }
        return null;
    }

    private String longestLabel(AccessibilityNodeInfo node, int depth, String best) {
        if (node == null || depth > 6) return best;
        String label = labelOf(node);
        if (label != null && !isStatusLabel(label)
                && (best == null || label.length() > best.length())) {
            best = label;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            best = longestLabel(node.getChild(i), depth + 1, best);
        }
        return best;
    }

    /** Row furniture rather than an app name: the button itself, a status, or a size. */
    private boolean isStatusLabel(String label) {
        String lower = label.toLowerCase(Locale.US);
        if (lower.equals("update") || lower.equals("update all") || lower.equals("cancel all")
                || lower.equals("cancel") || lower.equals("open") || lower.equals("more options")) {
            return true;
        }
        for (String marker : BUSY_MARKERS) {
            if (lower.startsWith(marker)) return true;
        }
        return lower.matches("^[0-9.,]+\\s*(b|kb|mb|gb)\\b.*") || lower.startsWith("pending");
    }

    private void maybeReopen(boolean playStoreShowing) {
        // Re-firing the deep link restarts the page's load from scratch. When Play Store is
        // already on screen and just slow, re-opening every couple of seconds means the load
        // NEVER completes - the page sits on a spinner (or a stale "up to date") forever. So
        // while Play Store is front, hold off much longer; the short cooldown is only for
        // shoving aside a woken app that is still covering the screen.
        long cooldown = playStoreShowing ? REOPEN_SETTLED_COOLDOWN_MS : REOPEN_COOLDOWN_MS;
        long now = System.currentTimeMillis();
        if (now - lastReopenAttempt >= cooldown) {
            openPlayStoreDownloads();
            lastReopenAttempt = now;
        }
    }

    /**
     * Play Store's root node, or null if it isn't on screen. Walks every window rather than
     * using getRootInActiveWindow(), which can hand back our own overlay window instead.
     */
    private AccessibilityNodeInfo findPlayStoreRoot() {
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows != null) {
            for (AccessibilityWindowInfo w : windows) {
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null) continue;
                CharSequence pkg = root.getPackageName();
                if (pkg != null && PLAY_STORE_PKG.contentEquals(pkg)) return root;
            }
        }
        // Fallback for devices/versions where getWindows() returns nothing usable.
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && root.getPackageName() != null
                && PLAY_STORE_PKG.contentEquals(root.getPackageName())) {
            return root;
        }
        return null;
    }

    /**
     * Full-tree DFS rather than findAccessibilityNodeInfosByText(), which can miss
     * Compose-rendered elements - and on the "Manage apps & device" Overview screen
     * "Update all" is a Compose text link, not a classic Button, so the old shallow
     * lookup found nothing clickable and never tapped it.
     */
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

            // Scrim + a rounded card, rather than one flat tinted box. Colours are literals
            // here on purpose: this is a service window with no activity theme behind it, so
            // there are no theme attributes to resolve. It sits over Play Store, which is
            // dark on most devices, so the card is dark too.
            LinearLayout scrim = new LinearLayout(this);
            scrim.setOrientation(LinearLayout.VERTICAL);
            scrim.setGravity(Gravity.CENTER);
            scrim.setBackgroundColor(Color.parseColor("#B3000000"));
            // The layout consumes every touch that lands on it, which is exactly what
            // blocks the user's input to whatever is underneath while the flow runs.
            scrim.setClickable(true);
            // A touch anywhere on the scrim brings the screen back up for a while. The run
            // is dimmed almost to black, and someone who walks over to check on it needs a
            // way to read the status and find Cancel that isn't guesswork. Children (the
            // Cancel button) get their own touches, so this doesn't swallow them.
            scrim.setOnTouchListener((v, event) -> {
                undimBriefly();
                return true;
            });

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER);
            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setColor(Color.parseColor("#1C1B1F"));
            cardBg.setCornerRadius(dp(28));
            card.setBackground(cardBg);
            card.setPadding(dp(28), dp(28), dp(28), dp(24));
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            cardLp.setMargins(dp(24), 0, dp(24), 0);
            card.setLayoutParams(cardLp);

            // Indeterminate spinner - a live "working" animation for the whole run.
            ProgressBar spinner = new ProgressBar(this);
            spinner.setIndeterminate(true);
            if (spinner.getIndeterminateDrawable() != null) {
                spinner.getIndeterminateDrawable().setColorFilter(
                        Color.parseColor("#D0BCFF"), PorterDuff.Mode.SRC_IN);
            }
            LinearLayout.LayoutParams spinnerLp = new LinearLayout.LayoutParams(
                    dp(40), dp(40));
            spinnerLp.bottomMargin = dp(20);
            spinner.setLayoutParams(spinnerLp);
            card.addView(spinner);

            overlayStatus = new TextView(this);
            overlayStatus.setTextColor(Color.parseColor("#E6E1E5"));
            overlayStatus.setTextSize(17);
            overlayStatus.setLineSpacing(dp(3), 1f);
            overlayStatus.setGravity(Gravity.CENTER);
            overlayStatus.setText("DeepWake is updating your apps...\nPlease don't touch the screen.");
            card.addView(overlayStatus);

            TextView hint = new TextView(this);
            hint.setTextColor(Color.parseColor("#938F99"));
            hint.setTextSize(13);
            hint.setGravity(Gravity.CENTER);
            hint.setPadding(0, dp(12), 0, dp(24));
            hint.setText("Apps will flash on screen while they are woken.\n"
                    + "The screen dims while this runs - touch it to see this again.");
            card.addView(hint);

            Button cancel = new Button(this);
            cancel.setText("Cancel");
            cancel.setAllCaps(false);
            cancel.setTextSize(15);
            cancel.setTextColor(Color.parseColor("#381E72"));
            cancel.setStateListAnimator(null);
            GradientDrawable cancelBg = new GradientDrawable();
            cancelBg.setColor(Color.parseColor("#D0BCFF"));
            cancelBg.setCornerRadius(dp(20));
            cancel.setBackground(cancelBg);
            LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
            cancel.setLayoutParams(cancelLp);
            cancel.setOnClickListener(v -> cancelFlow(true));
            card.addView(cancel);

            scrim.addView(card);
            LinearLayout box = scrim;

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
            // Dimming is done by the overlay window rather than by writing the system
            // brightness setting: no WRITE_SETTINGS permission, no value of the user's to
            // save and put back, and the override dies with the window - so the screen comes
            // back up on its own when the run ends, and equally if this service is killed or
            // switched off mid-run. Starts at normal brightness and drops after UNDIM_MS, so
            // the shade is readable when it appears.
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            windowManager.addView(box, lp);
            overlay = box;
            overlayParams = lp;
            handler.postDelayed(redim, UNDIM_MS);

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
        handler.removeCallbacks(redim);
        if (overlay != null && windowManager != null) {
            // Hand the screen back before the window goes, so the brightness is already up
            // when the report lands in front of the user. Removing the view would restore it
            // anyway - this only avoids the flash of a dark report.
            setOverlayBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE);
            try {
                windowManager.removeView(overlay);
            } catch (Exception ignored) {
            }
        }
        overlay = null;
        overlayParams = null;
        overlayStatus = null;
    }

    /** Full brightness now, back down to DIM_BRIGHTNESS once the user has stopped touching. */
    private void undimBriefly() {
        setOverlayBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE);
        handler.removeCallbacks(redim);
        handler.postDelayed(redim, UNDIM_MS);
    }

    private void setOverlayBrightness(float brightness) {
        if (overlay == null || overlayParams == null || windowManager == null) return;
        if (overlayParams.screenBrightness == brightness) return;
        overlayParams.screenBrightness = brightness;
        try {
            windowManager.updateViewLayout(overlay, overlayParams);
        } catch (Exception e) {
            // Same rule as the overlay itself - cosmetic, never worth breaking a run over.
            Log.w(TAG, "brightness update failed: " + e.getMessage());
        }
    }

    private void setOverlayStatus(String text) {
        if (overlayStatus != null) {
            overlayStatus.setText("DeepWake is updating your apps...\n\n" + text);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
