package com.pratiks360.deepwake;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NOTE: There is no official public API for "latest version of app X on Play Store".
 * This scrapes the public store listing HTML. Google can change the page structure
 * at any time, which will silently break this - if fetchLatestVersion() keeps returning
 * null for everything, the regex below needs updating to match the current page markup.
 * Use sparingly (one request per package per scan) to avoid looking like a bot.
 */
public class PlayStoreVersionFetcher {

    // Play Store embeds structured data like: "softwareVersion":"1.2.3"
    private static final Pattern VERSION_PATTERN = Pattern.compile("\"softwareVersion\":\"([^\"]+)\"");

    public static String fetchLatestVersion(String packageName) {
        String urlStr = "https://play.google.com/store/apps/details?id=" + packageName + "&hl=en";
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            StringBuilder html = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    html.append(line);
                }
            }

            Matcher matcher = VERSION_PATTERN.matcher(html);
            if (matcher.find()) {
                String version = matcher.group(1);
                // Play sometimes reports "Varies with device" instead of a real version
                if (version != null && !version.equalsIgnoreCase("Varies with device")) {
                    return version;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return null;
    }
}
