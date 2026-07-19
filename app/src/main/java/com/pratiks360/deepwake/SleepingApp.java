package com.pratiks360.deepwake;

public class SleepingApp {
    public String packageName;
    public String appName;
    public String currentVersion;
    public String latestVersion;
    // UI-only selection state for batch updates; deliberately not persisted.
    public boolean selected = true;

    public SleepingApp(String packageName, String appName, String currentVersion, String latestVersion) {
        this.packageName = packageName;
        this.appName = appName;
        this.currentVersion = currentVersion == null ? "" : currentVersion;
        this.latestVersion = latestVersion == null ? "" : latestVersion;
    }
}
