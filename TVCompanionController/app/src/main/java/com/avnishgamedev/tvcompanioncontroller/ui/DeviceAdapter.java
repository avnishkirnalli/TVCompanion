package com.avnishgamedev.tvcompanioncontroller.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.avnishgamedev.tvcompanioncontroller.R;
import com.avnishgamedev.tvcompanioncontroller.model.DiscoveredDevice;

import java.util.ArrayList;
import java.util.List;

public class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder> {

    private List<DiscoveredDevice> devices = new ArrayList<>();
    private final OnDeviceClickListener listener;

    public interface OnDeviceClickListener {
        void onDeviceClick(DiscoveredDevice device);
    }

    public DeviceAdapter(OnDeviceClickListener listener) {
        this.listener = listener;
    }

    public void setDevices(List<DiscoveredDevice> devices) {
        this.devices = devices;
        notifyDataSetChanged();
    }
    
    public void addDevice(DiscoveredDevice device) {
        if (!devices.contains(device)) {
            devices.add(device);
            notifyItemInserted(devices.size() - 1);
        }
    }


    @NonNull
    @Override
    public DeviceViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.device_list_item, parent, false);
        return new DeviceViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull DeviceViewHolder holder, int position) {
        DiscoveredDevice device = devices.get(position);
        holder.bind(device, listener);
    }

    @Override
    public int getItemCount() {
        return devices.size();
    }

    static class DeviceViewHolder extends RecyclerView.ViewHolder {
        private final TextView deviceName;
        private final TextView deviceAddress;

        public DeviceViewHolder(@NonNull View itemView) {
            super(itemView);
            deviceName = itemView.findViewById(R.id.deviceName);
            deviceAddress = itemView.findViewById(R.id.deviceAddress);
        }

        public void bind(final DiscoveredDevice device, final OnDeviceClickListener listener) {
            deviceName.setText(device.getName());
            deviceAddress.setText(device.getHostAddress() + ":" + device.getPort());
            itemView.setOnClickListener(v -> listener.onDeviceClick(device));
        }
    }
}