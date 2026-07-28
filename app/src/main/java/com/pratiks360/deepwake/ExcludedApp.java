package com.pratiks360.deepwake;

/** A package the user has excluded from scanning and updating. */
public class ExcludedApp {
    public final String packageName;
    public final String appName;

    public ExcludedApp(String packageName, String appName) {
        this.packageName = packageName;
        this.appName = appName == null || appName.isEmpty() ? packageName : appName;
    }
}
