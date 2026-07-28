package com.pratiks360.deepwake;

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
import java.util.List;

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
        SQLiteDatabase db = DeepWakeDb.get(context).getReadableDatabase();
        try (Cursor c = db.query(DeepWakeDb.T_APPS,
                new String[]{DeepWakeDb.C_PACKAGE, DeepWakeDb.C_APP_NAME,
                        DeepWakeDb.C_CURRENT_VERSION, DeepWakeDb.C_LATEST_VERSION},
                null, null, null, null, DeepWakeDb.C_APP_NAME + " COLLATE NOCASE ASC")) {
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
        DeepWakeDb.get(context).getWritableDatabase()
                .insertWithOnConflict(DeepWakeDb.T_APPS, null, toValues(app),
                        SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Insert-or-replace a whole set in one transaction. */
    public static void upsertApps(Context context, List<SleepingApp> apps) {
        migrateLegacyJson(context);
        SQLiteDatabase db = DeepWakeDb.get(context).getWritableDatabase();
        db.beginTransaction();
        try {
            for (SleepingApp app : apps) {
                db.insertWithOnConflict(DeepWakeDb.T_APPS, null, toValues(app),
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
        DeepWakeDb.get(context).getWritableDatabase().delete(DeepWakeDb.T_APPS,
                DeepWakeDb.C_PACKAGE + " = ?", new String[]{packageName});
    }

    private static ContentValues toValues(SleepingApp app) {
        ContentValues v = new ContentValues();
        v.put(DeepWakeDb.C_PACKAGE, app.packageName);
        v.put(DeepWakeDb.C_APP_NAME, app.appName == null ? app.packageName : app.appName);
        v.put(DeepWakeDb.C_CURRENT_VERSION, app.currentVersion == null ? "" : app.currentVersion);
        v.put(DeepWakeDb.C_LATEST_VERSION, app.latestVersion == null ? "" : app.latestVersion);
        v.put(DeepWakeDb.C_UPDATED_AT, System.currentTimeMillis());
        return v;
    }

    // ---------------------------------------------------------------- run reports

    /**
     * Records a finished run and prunes everything past the newest MAX_REPORTS, so the
     * history stays at five rows without any explicit housekeeping elsewhere.
     */
    public static long saveReport(Context context, List<BatchReport.Item> items,
                                  int total, int updatedCount) {
        SQLiteDatabase db = DeepWakeDb.get(context).getWritableDatabase();
        long id;
        db.beginTransaction();
        try {
            ContentValues report = new ContentValues();
            report.put(DeepWakeDb.C_FINISHED_AT, System.currentTimeMillis());
            report.put(DeepWakeDb.C_TOTAL, total);
            report.put(DeepWakeDb.C_UPDATED_COUNT, updatedCount);
            id = db.insert(DeepWakeDb.T_REPORTS, null, report);

            for (BatchReport.Item item : items) {
                ContentValues v = new ContentValues();
                v.put(DeepWakeDb.C_REPORT_ID, id);
                v.put(DeepWakeDb.C_APP_NAME, item.appName);
                v.put(DeepWakeDb.C_PACKAGE, item.packageName);
                v.put(DeepWakeDb.C_STATUS, item.status);
                v.put(DeepWakeDb.C_DETAIL, item.detail);
                db.insert(DeepWakeDb.T_REPORT_ITEMS, null, v);
            }

            // Items first, or the surviving-ids subquery no longer covers the rows we drop.
            String keep = "SELECT " + DeepWakeDb.C_ID + " FROM " + DeepWakeDb.T_REPORTS
                    + " ORDER BY " + DeepWakeDb.C_FINISHED_AT + " DESC LIMIT " + MAX_REPORTS;
            db.execSQL("DELETE FROM " + DeepWakeDb.T_REPORT_ITEMS
                    + " WHERE " + DeepWakeDb.C_REPORT_ID + " NOT IN (" + keep + ")");
            db.execSQL("DELETE FROM " + DeepWakeDb.T_REPORTS
                    + " WHERE " + DeepWakeDb.C_ID + " NOT IN (" + keep + ")");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return id;
    }

    /** The stored runs, newest first, with their per-app lines attached. */
    public static List<BatchReport> loadRecentReports(Context context) {
        List<BatchReport> reports = new ArrayList<>();
        SQLiteDatabase db = DeepWakeDb.get(context).getReadableDatabase();
        try (Cursor c = db.query(DeepWakeDb.T_REPORTS,
                new String[]{DeepWakeDb.C_ID, DeepWakeDb.C_FINISHED_AT,
                        DeepWakeDb.C_TOTAL, DeepWakeDb.C_UPDATED_COUNT},
                null, null, null, null, DeepWakeDb.C_FINISHED_AT + " DESC",
                String.valueOf(MAX_REPORTS))) {
            while (c.moveToNext()) {
                reports.add(new BatchReport(c.getLong(0), c.getLong(1), c.getInt(2), c.getInt(3)));
            }
        }
        for (BatchReport report : reports) {
            try (Cursor c = db.query(DeepWakeDb.T_REPORT_ITEMS,
                    new String[]{DeepWakeDb.C_APP_NAME, DeepWakeDb.C_PACKAGE,
                            DeepWakeDb.C_STATUS, DeepWakeDb.C_DETAIL},
                    DeepWakeDb.C_REPORT_ID + " = ?", new String[]{String.valueOf(report.id)},
                    null, null, DeepWakeDb.C_ID + " ASC")) {
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
        SQLiteDatabase db = DeepWakeDb.get(context).getWritableDatabase();
        db.beginTransaction();
        try {
            for (SleepingApp app : imported) {
                db.insertWithOnConflict(DeepWakeDb.T_APPS, null, toValues(app),
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
