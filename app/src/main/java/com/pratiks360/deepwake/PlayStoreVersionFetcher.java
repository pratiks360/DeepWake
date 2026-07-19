package com.pratiks360.deepwake;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scrapes the public Play Store listing page for the latest published version.
 *
 * No official API exists and Google changes the markup often, so this reads the RAW page
 * source (no browser/JS/DOM - XPaths do not apply here) and looks for the version string
 * the way Play Store embeds it inside its AF_initDataCallback script data.
 *
 * Failure returns diagnostic markers so the UI shows WHY it failed:
 *   real version (e.g. "2.127.1") -> success
 *   "net-error"                   -> request failed / blocked / no INTERNET
 *   "no-match"                    -> page fetched but no version token found (snippet logged)
 */
public class PlayStoreVersionFetcher {

    private static final String TAG = "DeepWakeScrape";

    public static final String NET_ERROR = "net-error";
    public static final String NO_MATCH = "no-match";

    // A version-shaped token: 2 to 5 dot-separated numeric groups, e.g. 2.127.1 or 1.2.3.4
    private static final Pattern VERSION_TOKEN =
            Pattern.compile("\"(\\d{1,4}(?:\\.\\d{1,4}){1,4})\"");

    // Legacy exact field, tried first if present
    private static final Pattern SOFTWARE_VERSION =
            Pattern.compile("\"softwareVersion\"\\s*:\\s*\"([^\"]+)\"");

    // Anchors that Play Store places NEAR the current version in the data arrays.
    private static final String[] ANCHORS = {
            "Varies with device", "Version", "Current Version", "Updated on", "What's New", "New features"
    };

    public static String fetchLatestVersion(String packageName) {
        String urlStr = "https://play.google.com/store/apps/details?id="
                + packageName + "&hl=en&gl=US";
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36");
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(12000);
            conn.setInstanceFollowRedirects(true);

            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, packageName + " HTTP " + code);
                return NET_ERROR;
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }
            String page = sb.toString();

            // 1) Legacy exact field
            Matcher sv = SOFTWARE_VERSION.matcher(page);
            if (sv.find()) {
                String v = sv.group(1);
                if (isRealVersion(v)) return v.trim();
            }

            // 2) Look for a version-shaped token NEAR any anchor word (best signal).
            String best = findNearAnchor(page);
            if (best != null) return best;

            // 3) Fallback: Play Store no longer exposes a labeled version field on the
            //    public listing page. The only version-shaped tokens left on the page are
            //    the per-review "app version" tags Play Store attaches to each review in
            //    the reviews carousel (the version the reviewer had installed at the time).
            best = maxVersionToken(page);
            if (best != null) return best;

            // Nothing usable - log a hint around the first anchor so we can refine.
            logHint(packageName, page);
            return NO_MATCH;

        } catch (Exception e) {
            Log.w(TAG, packageName + " net-error: " + e.getMessage());
            return NET_ERROR;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String findNearAnchor(String page) {
        for (String anchor : ANCHORS) {
            int from = 0;
            while (true) {
                int idx = page.indexOf(anchor, from);
                if (idx < 0) break;
                int start = Math.max(0, idx - 200);
                int end = Math.min(page.length(), idx + 200);
                String window = page.substring(start, end);
                Matcher m = VERSION_TOKEN.matcher(window);
                while (m.find()) {
                    String v = m.group(1);
                    if (isRealVersion(v)) return v.trim();
                }
                from = idx + anchor.length();
            }
        }
        return null;
    }

    // Reviewers skew toward whatever version they installed a while ago, so older
    // releases accumulate far more tagged reviews than the newest one - picking the
    // most-frequent token (the previous strategy) systematically returns a stale
    // version and can look like a "major mismatch" against the real current version.
    // The highest version actually seen is a much better lower-bound estimate.
    private static String maxVersionToken(String page) {
        Matcher m = VERSION_TOKEN.matcher(page);
        String best = null;
        while (m.find()) {
            String v = m.group(1);
            if (!isRealVersion(v)) continue;
            if (best == null || compareVersions(v, best) > 0) best = v;
        }
        return best;
    }

    // Numeric, dot-separated comparison (e.g. "2.26.9" < "2.26.10"), not lexicographic.
    private static int compareVersions(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int va = i < pa.length ? parseSegment(pa[i]) : 0;
            int vb = i < pb.length ? parseSegment(pb[i]) : 0;
            if (va != vb) return Integer.compare(va, vb);
        }
        return 0;
    }

    private static int parseSegment(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // Filters out things that are version-shaped but clearly not app versions
    // (screen densities, SDK numbers, timestamps split oddly, star ratings, etc.)
    private static boolean isRealVersion(String v) {
        if (v == null) return false;
        if (v.equalsIgnoreCase("Varies with device")) return false;
        String[] parts = v.split("\\.");
        if (parts.length < 2) return false;
        // reject absurdly long single groups (likely ids/timestamps)
        for (String p : parts) {
            if (p.length() > 4) return false;
        }
        // Play Store embeds its star rating as a bare "X.Y" token (e.g. "4.6", always
        // 0.0-5.0 with exactly one decimal digit) in several places on the page - that
        // shape is indistinguishable from a short version number except by range, and
        // it was winning the version-picking fallback above, which showed the rating as
        // the "latest version". Reject it.
        if (parts.length == 2 && parts[1].length() == 1) {
            try {
                double d = Double.parseDouble(v);
                if (d >= 0.0 && d <= 5.0) return false;
            } catch (NumberFormatException ignored) {
            }
        }
        return true;
    }

    private static void logHint(String packageName, String page) {
        for (String anchor : ANCHORS) {
            int idx = page.indexOf(anchor);
            if (idx >= 0) {
                int start = Math.max(0, idx - 60);
                int end = Math.min(page.length(), idx + 140);
                Log.w(TAG, packageName + " no-match near '" + anchor + "': "
                        + page.substring(start, end));
                return;
            }
        }
        Log.w(TAG, packageName + " no-match; page len=" + page.length()
                + " (no anchors found - page may be JS-only)");
    }
}