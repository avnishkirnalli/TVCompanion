package com.avnishgamedev.tvcompanioncontroller.network;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import com.avnishgamedev.tvcompanioncontroller.model.DiscoveredDevice;

public class NsdHelper {

    private static final String SERVICE_TYPE = "_tvcompanion._tcp.";

    private final NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;
    private NsdListener listener;

    public interface NsdListener {
        void onDeviceDiscovered(DiscoveredDevice device);
        void onDiscoveryFailed();
    }

    public NsdHelper(Context context, NsdListener listener) {
        this.nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        this.listener = listener;
    }

    public void startDiscovery() {
        stopDiscovery(); // Stop any previous discovery
        initializeDiscoveryListener();
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
    }

    public void stopDiscovery() {
        if (discoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener);
            } catch (IllegalArgumentException e) {
                // Listener not registered
            }
            discoveryListener = null;
        }
    }

    private void initializeDiscoveryListener() {
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String regType) {
            }

            @Override
            public void onServiceFound(NsdServiceInfo service) {
                nsdManager.resolveService(service, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    }

                    @Override
                    public void onServiceResolved(NsdServiceInfo serviceInfo) {
                        if (listener != null) {
                            listener.onDeviceDiscovered(new DiscoveredDevice(serviceInfo));
                        }
                    }
                });
            }

            @Override
            public void onServiceLost(NsdServiceInfo service) {
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                if (listener != null) {
                    listener.onDiscoveryFailed();
                }
                nsdManager.stopServiceDiscovery(this);
            }



            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                nsdManager.stopServiceDiscovery(this);
            }
        };
    }
}