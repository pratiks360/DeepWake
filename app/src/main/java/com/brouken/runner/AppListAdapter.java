package com.brouken.runner;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
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
        holder.latestVersion.setText("Latest: " + (app.latestVersion.isEmpty() ? "unknown" : app.latestVersion));
        holder.btnUpdate.setOnClickListener(v -> listener.onUpdateClick(app));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView name, currentVersion, latestVersion;
        Button btnUpdate;

        ViewHolder(View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.appName);
            currentVersion = itemView.findViewById(R.id.currentVersion);
            latestVersion = itemView.findViewById(R.id.latestVersion);
            btnUpdate = itemView.findViewById(R.id.btnUpdateSingle);
        }
    }
}
