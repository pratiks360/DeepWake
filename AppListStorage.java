package com.brouken.runner;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists the sleeping-apps list as a simple JSON file in app-private storage.
 * File survives app restarts and survives the tracked apps going back to deep sleep,
 * since we never rely on live PackageManager state to keep the list - only to refresh it.
 */
public class AppListStorage {

    private static final String FILE_NAME = "sleeping_apps.json";

    public static synchronized List<SleepingApp> load(Context context) {
        List<SleepingApp> list = new ArrayList<>();
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (!file.exists()) {
            return list;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                list.add(new SleepingApp(
                        obj.getString("packageName"),
                        obj.optString("appName", obj.getString("packageName")),
                        obj.optString("currentVersion", ""),
                        obj.optString("latestVersion", "")
                ));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }

    public static synchronized void save(Context context, List<SleepingApp> list) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        try (FileWriter writer = new FileWriter(file)) {
            JSONArray arr = new JSONArray();
            for (SleepingApp app : list) {
                JSONObject obj = new JSONObject();
                obj.put("packageName", app.packageName);
                obj.put("appName", app.appName);
                obj.put("currentVersion", app.currentVersion);
                obj.put("latestVersion", app.latestVersion);
                arr.put(obj);
            }
            writer.write(arr.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
