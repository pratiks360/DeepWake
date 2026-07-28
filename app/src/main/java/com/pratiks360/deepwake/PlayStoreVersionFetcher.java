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
 *   "not-published"               -> the listing publishes no version ("Varies with device")
 *   "no-match"                    -> page fetched but no version token found (snippet logged)
 */
public class PlayStoreVersionFetcher {

    private static final String TAG = "DeepWakeScrape";

    public static final String NET_ERROR = "net-error";
    public static final String NO_MATCH = "no-match";
    public static final String NO_VERSION = "not-published";
    /** Placeholder a row carries between being listed by a scan and its lookup landing. */
    public static final String CHECKING = "checking...";

    // A version-shaped token: 2 to 5 dot-separated numeric groups, e.g. 2.127.1 or 1.2.3.4
    private static final Pattern VERSION_TOKEN =
            Pattern.compile("\"(\\d{1,4}(?:\\.\\d{1,4}){1,4})\"");

    // Legacy exact field, tried first if present
    private static final Pattern SOFTWARE_VERSION =
            Pattern.compile("\"softwareVersion\"\\s*:\\s*\"([^\"]+)\"");

    // The app-details cluster inside Play Store's embedded data. The published version sits
    // alone in a triple-nested array immediately before the "requires Android" block:
    //     [[["2.19.300"]],[[[35]],[[[26,"8.0"]]]]],...,[[null,[null,"<what's new>"]]],...
    // This is the ONLY authoritative version on the page. Every other version-shaped token
    // is a per-review tag - the version each reviewer happened to have installed.
    private static final Pattern DETAILS_VERSION =
            Pattern.compile("\\[\\[\\[\"([^\"]{1,40})\"\\]\\],\\[\\[\\[");
    // Same slot without the trailing requirements block, for listings that omit it.
    private static final Pattern DETAILS_VERSION_LOOSE =
            Pattern.compile("\\[\\[\\[\"([^\"]{1,40})\"\\]\\]");
    // Same slot, explicitly empty: the app ships per-device builds and Play Store publishes
    // no single version for it (the listing shows "Varies with device").
    private static final Pattern DETAILS_NO_VERSION =
            Pattern.compile("\\[\\[null,\\[\\]\\],\\[\\[\\[");

    // Anchors that Play Store places NEAR the current version in the data arrays.
    private static final String[] ANCHORS = {
            "Varies with device", "Version", "Current Version", "Updated on", "What's New", "New features"
    };

    /**
     * The version Play Store publishes for this package, or one of the markers above. Takes
     * no installed version any more: the old candidate-disambiguation needed it, but the
     * details slot this reads now is unambiguous.
     */
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

            // 2) The published version in the app-details cluster - the authoritative one.
            String best = detailsVersion(page);
            if (best != null) return best;

            // 3) The listing says the version varies per device, so there is no single
            //    "latest" to compare against. Distinct from NO_MATCH: nothing is broken,
            //    Play Store simply doesn't publish one.
            if (DETAILS_NO_VERSION.matcher(page).find()) return NO_VERSION;

            // 4) Older/regional markup: a version-shaped token NEAR a label word.
            best = findNearAnchor(page);
            if (best != null) return best;

            // Nothing usable - log a hint around the first anchor so we can refine.
            //
            // There used to be a further fallback here that took the highest version-shaped
            // token anywhere on the page, filtered to the installed version's segment count
            // and leading segment. Those tokens are the per-review "app version" tags (the
            // build each reviewer had installed), so the maximum is whatever the most
            // adventurous reviewer was running - or noise that happens to share the shape.
            // It reported HDFC Home Loans 5.5 as "latest 5.32" and Tank Stars 2.19.300 as
            // "latest 2.20.0", both already up to date. A wrong version costs a whole wake +
            // batch-update cycle chasing an update that doesn't exist, so NO_MATCH it is.
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

    /**
     * Reads the published version out of the app-details cluster. The strict form (version
     * slot followed by the requirements block) is what current listings emit; the loose form
     * is the same slot without it. Both are validated as version-shaped, which is what keeps
     * the loose form from picking up the neighbouring string keys.
     */
    private static String detailsVersion(String page) {
        Matcher strict = DETAILS_VERSION.matcher(page);
        if (strict.find() && looksLikeVersion(strict.group(1))) return strict.group(1).trim();
        Matcher loose = DETAILS_VERSION_LOOSE.matcher(page);
        while (loose.find()) {
            if (looksLikeVersion(loose.group(1))) return loose.group(1).trim();
        }
        return null;
    }

    /**
     * Starts with a digit and has at least one dot between digits. Looser than isRealVersion
     * on purpose - this only ever sees the details slot, where a build tag like "5.2628.1-hf"
     * is the real answer, not noise to be filtered.
     */
    private static boolean looksLikeVersion(String v) {
        return v != null && v.matches("^\\d[\\w.-]*\\.[\\w.-]*\\d.*");
    }

    /**
     * True when the stored value is a version that can actually be compared, as opposed to a
     * placeholder or one of the failure markers. Every caller that reasons about versions
     * goes through here - a missed marker check reads "net-error" as a version number.
     */
    public static boolean isUsableVersion(String version) {
        return version != null && !version.isEmpty()
                && !version.equals(CHECKING)
                && !version.equals(NET_ERROR)
                && !version.equals(NO_MATCH)
                && !version.equals(NO_VERSION);
    }

    /** True if latest is a strictly newer version than current (numeric, not lexicographic). */
    public static boolean isNewerVersion(String latest, String current) {
        if (latest == null || current == null || latest.isEmpty() || current.isEmpty()) return false;
        return compareVersions(latest, current) > 0;
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