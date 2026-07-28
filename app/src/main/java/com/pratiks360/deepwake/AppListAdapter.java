package com.pratiks360.deepwake;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;

import java.util.List;

public class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.ViewHolder> {

    public interface OnUpdateClickListener {
        void onUpdateClick(SleepingApp app);
    }

    /** Long-press: the row offers to stop tracking this app for good. */
    public interface OnExcludeListener {
        void onExcludeRequested(SleepingApp app);
    }

    private final List<SleepingApp> items;
    private final OnUpdateClickListener listener;
    private final OnExcludeListener excludeListener;
    private final Runnable onSelectionChanged;

    public AppListAdapter(List<SleepingApp> items, OnUpdateClickListener listener,
                          OnExcludeListener excludeListener, Runnable onSelectionChanged) {
        this.items = items;
        this.listener = listener;
        this.excludeListener = excludeListener;
        this.onSelectionChanged = onSelectionChanged;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        SleepingApp app = items.get(position);
        holder.name.setText(app.appName);

        String current = app.currentVersion.isEmpty() ? "?" : app.currentVersion;
        String latest = app.latestVersion == null ? "" : app.latestVersion;
        String versionLine;
        boolean outdated = false;

        if (latest.equals(PlayStoreVersionFetcher.CHECKING)) {
            versionLine = current + "  ·  checking Play Store…";
        } else if (latest.equals(PlayStoreVersionFetcher.NO_VERSION)) {
            // Play Store ships this app per-device and publishes no version for it, so
            // there is nothing to compare against - not a failure on our side.
            versionLine = current + "  ·  version varies with device";
        } else if (latest.equals(PlayStoreVersionFetcher.NET_ERROR)) {
            versionLine = current + "  ·  Play Store unreachable";
        } else if (!PlayStoreVersionFetcher.isUsableVersion(latest)) {
            versionLine = current + "  ·  latest unknown";
        } else {
            outdated = PlayStoreVersionFetcher.isNewerVersion(latest, app.currentVersion);
            versionLine = outdated ? current + "  →  " + latest : current + "  ·  up to date";
        }

        holder.versionLine.setText(versionLine);
        // Own colour resources rather than a library R.attr lookup: with non-transitive R
        // classes there is no promise about which library's R carries a given attr, and
        // values-night gives the dark-mode variant anyway.
        holder.versionLine.setTextColor(ContextCompat.getColor(holder.itemView.getContext(),
                outdated ? R.color.version_outdated : R.color.version_muted));

        holder.btnUpdate.setEnabled(outdated);
        holder.btnUpdate.setOnClickListener(v -> listener.onUpdateClick(app));

        // Recycled rows keep their old listener - detach it before setChecked, or binding
        // a row would clobber the selection state of whichever app previously used it.
        holder.cbSelect.setOnCheckedChangeListener(null);
        holder.cbSelect.setChecked(app.selected);
        holder.cbSelect.setOnCheckedChangeListener((btn, checked) -> {
            app.selected = checked;
            if (onSelectionChanged != null) onSelectionChanged.run();
        });

        // Tapping anywhere on the row toggles selection (the Update button has its own
        // click handler and swallows the touch, so it won't also flip the checkbox).
        holder.itemView.setOnClickListener(v -> holder.cbSelect.toggle());
        holder.itemView.setOnLongClickListener(v -> {
            if (excludeListener != null) excludeListener.onExcludeRequested(app);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView name, versionLine;
        MaterialButton btnUpdate;
        MaterialCheckBox cbSelect;

        ViewHolder(View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.appName);
            versionLine = itemView.findViewById(R.id.versionLine);
            btnUpdate = itemView.findViewById(R.id.btnUpdateSingle);
            cbSelect = itemView.findViewById(R.id.cbSelect);
        }
    }
}
