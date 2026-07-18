package com.pratiks360.deepwake;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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

            // 3) Fallback: the most frequent version-shaped token on the page is
            //    very often the current version (it repeats in several data blocks).
            best = mostFrequentVersionToken(page);
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

    private static String mostFrequentVersionToken(String page) {
        List<String> tokens = new ArrayList<>();
        Matcher m = VERSION_TOKEN.matcher(page);
        while (m.find()) {
            String v = m.group(1);
            if (isRealVersion(v)) tokens.add(v);
        }
        if (tokens.isEmpty()) return null;
        String bestVal = null;
        int bestCount = 0;
        for (String t : tokens) {
            int c = 0;
            for (String o : tokens) if (o.equals(t)) c++;
            if (c > bestCount) { bestCount = c; bestVal = t; }
        }
        return bestVal;
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
        // it appears often enough to win the frequency-based fallback below, which was
        // showing the rating as the "latest version". Reject it.
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