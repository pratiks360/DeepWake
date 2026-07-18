package com.brouken.runner;

public class SleepingApp {
    public String packageName;
    public String appName;
    public String currentVersion;
    public String latestVersion;

    public SleepingApp(String packageName, String appName, String currentVersion, String latestVersion) {
        this.packageName = packageName;
        this.appName = appName;
        this.currentVersion = currentVersion == null ? "" : currentVersion;
        this.latestVersion = latestVersion == null ? "" : latestVersion;
    }
}
