package com.pratiks360.deepwake;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * The app's SQLite store. Replaces the old sleeping_apps.json file: a whole-file rewrite on
 * every save couldn't express "just this one app changed", had no room for run history, and
 * lost everything if the process died mid-write. Three tables:
 *
 *   apps         - the tracked sleeping apps (one row per package)
 *   reports      - one row per finished batch run, newest 5 kept
 *   report_items - the per-app lines of each report
 *   excluded     - packages the user never wants touched; kept in its own table so an
 *                  exclusion outlives the app row it was made from (rows come and go as
 *                  apps update), and so a scan can skip the package before doing any work
 *
 * Plain SQLiteOpenHelper rather than Room: the schema is three flat tables with no
 * relations worth modelling, and this keeps the build free of annotation processing.
 * Access goes through AppRepository, never directly.
 */
public class DeepWakeDb extends SQLiteOpenHelper {

    private static final String NAME = "deepwake.db";
    private static final int VERSION = 2;   // 2: added the excluded table

    static final String T_APPS = "apps";
    static final String C_PACKAGE = "package_name";
    static final String C_APP_NAME = "app_name";
    static final String C_CURRENT_VERSION = "current_version";
    static final String C_LATEST_VERSION = "latest_version";
    static final String C_UPDATED_AT = "updated_at";

    static final String T_REPORTS = "reports";
    static final String C_ID = "_id";
    static final String C_FINISHED_AT = "finished_at";
    static final String C_TOTAL = "total";
    static final String C_UPDATED_COUNT = "updated_count";

    static final String T_REPORT_ITEMS = "report_items";
    static final String C_REPORT_ID = "report_id";
    static final String C_STATUS = "status";
    static final String C_DETAIL = "detail";

    static final String T_EXCLUDED = "excluded";
    static final String C_EXCLUDED_AT = "excluded_at";

    private static DeepWakeDb instance;

    /** One helper per process - SQLiteOpenHelper is thread-safe, multiple helpers are not. */
    static synchronized DeepWakeDb get(Context context) {
        if (instance == null) {
            instance = new DeepWakeDb(context.getApplicationContext());
        }
        return instance;
    }

    private DeepWakeDb(Context context) {
        super(context, NAME, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + T_APPS + " ("
                + C_PACKAGE + " TEXT PRIMARY KEY, "
                + C_APP_NAME + " TEXT NOT NULL, "
                + C_CURRENT_VERSION + " TEXT NOT NULL DEFAULT '', "
                + C_LATEST_VERSION + " TEXT NOT NULL DEFAULT '', "
                + C_UPDATED_AT + " INTEGER NOT NULL DEFAULT 0)");

        db.execSQL("CREATE TABLE " + T_REPORTS + " ("
                + C_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + C_FINISHED_AT + " INTEGER NOT NULL, "
                + C_TOTAL + " INTEGER NOT NULL DEFAULT 0, "
                + C_UPDATED_COUNT + " INTEGER NOT NULL DEFAULT 0)");

        db.execSQL("CREATE TABLE " + T_REPORT_ITEMS + " ("
                + C_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + C_REPORT_ID + " INTEGER NOT NULL, "
                + C_APP_NAME + " TEXT NOT NULL, "
                + C_PACKAGE + " TEXT NOT NULL DEFAULT '', "
                + C_STATUS + " TEXT NOT NULL, "
                + C_DETAIL + " TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX idx_report_items_report ON "
                + T_REPORT_ITEMS + "(" + C_REPORT_ID + ")");

        createExcluded(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Migrate, never drop - the tracked list is expensive to rebuild (a full scan plus
        // a Play Store lookup per app).
        if (oldVersion < 2) createExcluded(db);
    }

    private void createExcluded(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + T_EXCLUDED + " ("
                + C_PACKAGE + " TEXT PRIMARY KEY, "
                + C_APP_NAME + " TEXT NOT NULL, "
                + C_EXCLUDED_AT + " INTEGER NOT NULL DEFAULT 0)");
    }
}
