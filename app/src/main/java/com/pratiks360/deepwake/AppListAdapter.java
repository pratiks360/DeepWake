package com.pratiks360.deepwake;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.ViewHolder> {

    public interface OnUpdateClickListener {
        void onUpdateClick(SleepingApp app);
    }

    private final List<SleepingApp> items;
    private final OnUpdateClickListener listener;

    public AppListAdapter(List<SleepingApp> items, OnUpdateClickListener listener) {
        this.items = items;
        this.listener = listener;
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
        holder.currentVersion.setText("Current: " + (app.currentVersion.isEmpty() ? "?" : app.currentVersion));

        String latest = app.latestVersion == null ? "" : app.latestVersion;
        String latestLabel;
        boolean outdated = false;
        boolean enableBtn = false;

        if (latest.equals("checking...")) {
            latestLabel = "Latest: checking...";
        } else if (latest.isEmpty() || latest.equals(PlayStoreVersionFetcher.NO_MATCH)) {
            latestLabel = "Latest: unknown (couldn't read Play Store)";
        } else if (latest.equals(PlayStoreVersionFetcher.NET_ERROR)) {
            latestLabel = "Latest: unavailable (network)";
        } else {
            latestLabel = "Latest: " + latest;
            outdated = PlayStoreVersionFetcher.isNewerVersion(latest, app.currentVersion);
            enableBtn = outdated;
        }
        holder.latestVersion.setText(latestLabel);

        if (outdated) {
            holder.latestVersion.setTextColor(Color.parseColor("#D32F2F"));
        } else {
            holder.latestVersion.setTextColor(Color.parseColor("#388E3C"));
        }

        holder.btnUpdate.setEnabled(enableBtn);
        holder.btnUpdate.setOnClickListener(v -> listener.onUpdateClick(app));

        // Recycled rows keep their old listener - detach it before setChecked, or binding
        // a row would clobber the selection state of whichever app previously used it.
        holder.cbSelect.setOnCheckedChangeListener(null);
        holder.cbSelect.setChecked(app.selected);
        holder.cbSelect.setOnCheckedChangeListener((btn, checked) -> app.selected = checked);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView name, currentVersion, latestVersion;
        Button btnUpdate;
        CheckBox cbSelect;

        ViewHolder(View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.appName);
            currentVersion = itemView.findViewById(R.id.currentVersion);
            latestVersion = itemView.findViewById(R.id.latestVersion);
            btnUpdate = itemView.findViewById(R.id.btnUpdateSingle);
            cbSelect = itemView.findViewById(R.id.cbSelect);
        }
    }
}