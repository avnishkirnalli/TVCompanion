package com.avnishgamedev.tvcompanioncontroller.model;

import android.net.nsd.NsdServiceInfo;

public class DiscoveredDevice {
    private final String name;
    private final String hostAddress;
    private final int port;

    public DiscoveredDevice(NsdServiceInfo serviceInfo) {
        this.name = serviceInfo.getServiceName();
        this.hostAddress = serviceInfo.getHost().getHostAddress();
        this.port = serviceInfo.getPort();
    }

    public String getName() {
        return name;
    }

    public String getHostAddress() {
        return hostAddress;
    }

    public int getPort() {
        return port;
    }
}