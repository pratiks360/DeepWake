package com.updatehelper.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Every read/write of persisted state goes through here - the tracked sleeping apps and the
 * batch-run report history. Replaces AppListStorage's whole-file JSON rewrites: single-app
 * changes now touch a single row, and a run's report survives as queryable history instead
 * of living only in an Intent extra.
 *
 * Callers are a mix of the main thread (MainActivity), a foreground service's worker threads
 * (ScanService's fetch pool) and the accessibility service. SQLiteDatabase serializes its own
 * access, and the helper is a per-process singleton, so concurrent calls here are safe.
 */
public class AppRepository {

    /** How many finished runs are kept; older ones are pruned as new ones land. */
    private static final int MAX_REPORTS = 5;

    private static final String LEGACY_FILE = "sleeping_apps.json";
    private static boolean legacyChecked;

    private AppRepository() {
    }

    // ---------------------------------------------------------------- tracked apps

    public static List<SleepingApp> loadApps(Context context) {
        migrateLegacyJson(context);
        List<SleepingApp> list = new ArrayList<>();
        SQLiteDatabase db = UpdateHelperDb.get(context).getReadableDatabase();
        try (Cursor c = db.query(UpdateHelperDb.T_APPS,
                new String[]{UpdateHelperDb.C_PACKAGE, UpdateHelperDb.C_APP_NAME,
                        UpdateHelperDb.C_CURRENT_VERSION, UpdateHelperDb.C_LATEST_VERSION},
                null, null, null, null, UpdateHelperDb.C_APP_NAME + " COLLATE NOCASE ASC")) {
            while (c.moveToNext()) {
                list.add(new SleepingApp(c.getString(0), c.getString(1),
                        c.getString(2), c.getString(3)));
            }
        }
        return list;
    }

    /** Insert-or-replace one app. The scan uses this per finished lookup. */
    public static void upsertApp(Context context, SleepingApp app) {
        migrateLegacyJson(context);
        UpdateHelperDb.get(context).getWritableDatabase()
                .insertWithOnConflict(UpdateHelperDb.T_APPS, null, toValues(app),
                        SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Insert-or-replace a whole set in one transaction. */
    public static void upsertApps(Context context, List<SleepingApp> apps) {
        migrateLegacyJson(context);
        SQLiteDatabase db = UpdateHelperDb.get(context).getWritableDatabase();
        db.beginTransaction();
        try {
            for (SleepingApp app : apps) {
                db.insertWithOnConflict(UpdateHelperDb.T_APPS, null, toValues(app),
                        SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Stop tracking an app - it updated, so there is nothing left to do for it. */
    public static void removeApp(Context context, String packageName) {
        migrateLegacyJson(context);
        UpdateHelperDb.get(context).getWritableDatabase().delete(UpdateHelperDb.T_APPS,
                UpdateHelperDb.C_PACKAGE + " = ?", new String[]{packageName});
    }

    private static ContentValues toValues(SleepingApp app) {
        ContentValues v = new ContentValues();
        v.put(UpdateHelperDb.C_PACKAGE, app.packageName);
        v.put(UpdateHelperDb.C_APP_NAME, app.appName == null ? app.packageName : app.appName);
        v.put(UpdateHelperDb.C_CURRENT_VERSION, app.currentVersion == null ? "" : app.currentVersion);
        v.put(UpdateHelperDb.C_LATEST_VERSION, app.latestVersion == null ? "" : app.latestVersion);
        v.put(UpdateHelperDb.C_UPDATED_AT, System.currentTimeMillis());
        return v;
    }

    // ---------------------------------------------------------------- exclusions

    /**
     * Never touch this app again: it stops being tracked now, and scans skip the package
     * outright from here on (no Play Store lookup, no row, no place in a batch).
     */
    public static void excludeApp(Context context, String packageName, String appName) {
        migrateLegacyJson(context);
        SQLiteDatabase db = UpdateHelperDb.get(context).getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues v = new ContentValues();
            v.put(UpdateHelperDb.C_PACKAGE, packageName);
            v.put(UpdateHelperDb.C_APP_NAME, appName == null || appName.isEmpty()
                    ? packageName : appName);
            v.put(UpdateHelperDb.C_EXCLUDED_AT, System.currentTimeMillis());
            db.insertWithOnConflict(UpdateHelperDb.T_EXCLUDED, null, v,
                    SQLiteDatabase.CONFLICT_REPLACE);
            db.delete(UpdateHelperDb.T_APPS, UpdateHelperDb.C_PACKAGE + " = ?",
                    new String[]{packageName});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Undo an exclusion. The app reappears after the next scan, not immediately. */
    public static void includeApp(Context context, String packageName) {
        UpdateHelperDb.get(context).getWritableDatabase().delete(UpdateHelperDb.T_EXCLUDED,
                UpdateHelperDb.C_PACKAGE + " = ?", new String[]{packageName});
    }

    /** Just the package names - what a scan checks each installed app against. */
    public static Set<String> excludedPackages(Context context) {
        Set<String> out = new HashSet<>();
        SQLiteDatabase db = UpdateHelperDb.get(context).getReadableDatabase();
        try (Cursor c = db.query(UpdateHelperDb.T_EXCLUDED, new String[]{UpdateHelperDb.C_PACKAGE},
                null, null, null, null, null)) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }

    /** The excluded apps with their names, for the "Excluded apps" screen. */
    public static List<ExcludedApp> loadExcluded(Context context) {
        List<ExcludedApp> out = new ArrayList<>();
        SQLiteDatabase db = UpdateHelperDb.get(context).getReadableDatabase();
        try (Cursor c = db.query(UpdateHelperDb.T_EXCLUDED,
                new String[]{UpdateHelperDb.C_PACKAGE, UpdateHelperDb.C_APP_NAME},
                null, null, null, null, UpdateHelperDb.C_APP_NAME + " COLLATE NOCASE ASC")) {
            while (c.moveToNext()) out.add(new ExcludedApp(c.getString(0), c.getString(1)));
        }
        return out;
    }

    // ---------------------------------------------------------------- run reports

    /**
     * Records a finished run and prunes everything past the newest MAX_REPORTS, so the
     * history stays at five rows without any explicit housekeeping elsewhere.
     */
    public static long saveReport(Context context, List<BatchReport.Item> items,
                                  int total, int updatedCount) {
        SQLiteDatabase db = UpdateHelperDb.get(context).getWritableDatabase();
        long id;
        db.beginTransaction();
        try {
            ContentValues report = new ContentValues();
            report.put(UpdateHelperDb.C_FINISHED_AT, System.currentTimeMillis());
            report.put(UpdateHelperDb.C_TOTAL, total);
            report.put(UpdateHelperDb.C_UPDATED_COUNT, updatedCount);
            id = db.insert(UpdateHelperDb.T_REPORTS, null, report);

            for (BatchReport.Item item : items) {
                ContentValues v = new ContentValues();
                v.put(UpdateHelperDb.C_REPORT_ID, id);
                v.put(UpdateHelperDb.C_APP_NAME, item.appName);
                v.put(UpdateHelperDb.C_PACKAGE, item.packageName);
                v.put(UpdateHelperDb.C_STATUS, item.status);
                v.put(UpdateHelperDb.C_DETAIL, item.detail);
                db.insert(UpdateHelperDb.T_REPORT_ITEMS, null, v);
            }

            // Items first, or the surviving-ids subquery no longer covers the rows we drop.
            String keep = "SELECT " + UpdateHelperDb.C_ID + " FROM " + UpdateHelperDb.T_REPORTS
                    + " ORDER BY " + UpdateHelperDb.C_FINISHED_AT + " DESC LIMIT " + MAX_REPORTS;
            db.execSQL("DELETE FROM " + UpdateHelperDb.T_REPORT_ITEMS
                    + " WHERE " + UpdateHelperDb.C_REPORT_ID + " NOT IN (" + keep + ")");
            db.execSQL("DELETE FROM " + UpdateHelperDb.T_REPORTS
                    + " WHERE " + UpdateHelperDb.C_ID + " NOT IN (" + keep + ")");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return id;
    }

    /** The stored runs, newest first, with their per-app lines attached. */
    public static List<BatchReport> loadRecentReports(Context context) {
        List<BatchReport> reports = new ArrayList<>();
        SQLiteDatabase db = UpdateHelperDb.get(context).getReadableDatabase();
        try (Cursor c = db.query(UpdateHelperDb.T_REPORTS,
                new String[]{UpdateHelperDb.C_ID, UpdateHelperDb.C_FINISHED_AT,
                        UpdateHelperDb.C_TOTAL, UpdateHelperDb.C_UPDATED_COUNT},
                null, null, null, null, UpdateHelperDb.C_FINISHED_AT + " DESC",
                String.valueOf(MAX_REPORTS))) {
            while (c.moveToNext()) {
                reports.add(new BatchReport(c.getLong(0), c.getLong(1), c.getInt(2), c.getInt(3)));
            }
        }
        for (BatchReport report : reports) {
            try (Cursor c = db.query(UpdateHelperDb.T_REPORT_ITEMS,
                    new String[]{UpdateHelperDb.C_APP_NAME, UpdateHelperDb.C_PACKAGE,
                            UpdateHelperDb.C_STATUS, UpdateHelperDb.C_DETAIL},
                    UpdateHelperDb.C_REPORT_ID + " = ?", new String[]{String.valueOf(report.id)},
                    null, null, UpdateHelperDb.C_ID + " ASC")) {
                while (c.moveToNext()) {
                    report.items.add(new BatchReport.Item(
                            c.getString(0), c.getString(1), c.getString(2), c.getString(3)));
                }
            }
        }
        return reports;
    }

    // ---------------------------------------------------------------- legacy import

    /**
     * Carries an existing install's sleeping_apps.json into the DB the first time it's
     * touched, then deletes it. Without this an upgrade would silently start from an empty
     * list, and rebuilding it costs a full scan plus a Play Store lookup per app.
     */
    private static synchronized void migrateLegacyJson(Context context) {
        if (legacyChecked) return;
        legacyChecked = true;
        File file = new File(context.getApplicationContext().getFilesDir(), LEGACY_FILE);
        if (!file.exists()) return;
        List<SleepingApp> imported = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                imported.add(new SleepingApp(
                        obj.getString("packageName"),
                        obj.optString("appName", obj.getString("packageName")),
                        obj.optString("currentVersion", ""),
                        obj.optString("latestVersion", "")));
            }
        } catch (Exception e) {
            return; // unreadable/corrupt - leave the file alone and start clean
        }
        SQLiteDatabase db = UpdateHelperDb.get(context).getWritableDatabase();
        db.beginTransaction();
        try {
            for (SleepingApp app : imported) {
                db.insertWithOnConflict(UpdateHelperDb.T_APPS, null, toValues(app),
                        SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
