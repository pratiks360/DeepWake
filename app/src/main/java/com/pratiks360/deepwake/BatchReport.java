package com.pratiks360.deepwake;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** One finished batch run, as stored in the reports/report_items tables. */
public class BatchReport {

    public static final String STATUS_UPDATED = "updated";
    public static final String STATUS_ALREADY_CURRENT = "already_current";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_PENDING = "pending";

    public static class Item {
        public final String appName;
        public final String packageName;
        public final String status;
        public final String detail;

        public Item(String appName, String packageName, String status, String detail) {
            this.appName = appName;
            this.packageName = packageName == null ? "" : packageName;
            this.status = status;
            this.detail = detail == null ? "" : detail;
        }

        /** ✓ for anything that needs no further action, ✗ for what is still outstanding. */
        public String bullet() {
            return STATUS_UPDATED.equals(status) || STATUS_ALREADY_CURRENT.equals(status)
                    ? "  ✓ " : "  ✗ ";
        }
    }

    public long id;
    public long finishedAt;
    public int total;
    public int updatedCount;
    public final List<Item> items = new ArrayList<>();

    public BatchReport(long id, long finishedAt, int total, int updatedCount) {
        this.id = id;
        this.finishedAt = finishedAt;
        this.total = total;
        this.updatedCount = updatedCount;
    }

    /** Row label in the reports list, e.g. "28 Jul 2026, 12:26 pm  -  18/22 updated". */
    public String rowLabel() {
        SimpleDateFormat fmt = new SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault());
        return fmt.format(new Date(finishedAt)) + "\n" + updatedCount + "/" + total + " updated";
    }

    public String title() {
        SimpleDateFormat fmt = new SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault());
        return "Report - " + fmt.format(new Date(finishedAt));
    }

    /** The report body, grouped the same way the end-of-run dialog shows it. */
    public String body() {
        List<Item> done = new ArrayList<>();
        List<Item> outstanding = new ArrayList<>();
        for (Item item : items) {
            if (STATUS_UPDATED.equals(item.status) || STATUS_ALREADY_CURRENT.equals(item.status)) {
                done.add(item);
            } else {
                outstanding.add(item);
            }
        }
        StringBuilder sb = new StringBuilder();
        if (done.isEmpty()) {
            sb.append("No apps were updated.\n");
        } else {
            sb.append("Updated (").append(done.size()).append("):\n");
            for (Item item : done) {
                sb.append(item.bullet()).append(item.appName);
                if (!item.detail.isEmpty()) sb.append(" (").append(item.detail).append(")");
                sb.append("\n");
            }
        }
        if (!outstanding.isEmpty()) {
            sb.append("\nNot updated (").append(outstanding.size()).append("):\n");
            for (Item item : outstanding) {
                sb.append(item.bullet()).append(item.appName);
                if (!item.detail.isEmpty()) sb.append(" (").append(item.detail).append(")");
                sb.append("\n");
            }
        }
        return sb.toString().trim();
    }
}
