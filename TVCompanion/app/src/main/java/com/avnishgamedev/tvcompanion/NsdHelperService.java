package com.avnishgamedev.tvcompanion;

import android.app.Service;
import android.content.Intent;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

public class NsdHelperService extends Service {
    private static final String TAG = "NsdHelperService";

    // For Binding
    private final IBinder binder = new LocalBinder();
    public class LocalBinder extends Binder {
        NsdHelperService getService() {
            return NsdHelperService.this;
        }
    }

    // NSD
    private static final String SERVICE_TYPE = "_tvcompanion._tcp";
    private NsdManager nsdManager;
    boolean nsdRegistered = false;
    private final NsdManager.RegistrationListener regListener = new NsdManager.RegistrationListener() {
        @Override public void onServiceRegistered(NsdServiceInfo info) {
            nsdRegistered = true;
            Log.d(TAG, "Service Registered: " + info.getServiceName());
        }
        @Override public void onRegistrationFailed(NsdServiceInfo info, int errorCode) {
            nsdRegistered = false;
            Log.e(TAG, "Service Registration Failed: " + errorCode);
        }
        @Override public void onServiceUnregistered(NsdServiceInfo info) {
            nsdRegistered = false;
            Log.d(TAG, "Service Unregistered: " + info.getServiceName());
        }
        @Override public void onUnregistrationFailed(NsdServiceInfo info, int errorCode) {
            nsdRegistered = true;
            Log.e(TAG, "Service Unregistration Failed: " + errorCode);
        }
    };

    // Called when CompanionService binds to it.
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "NsdHelperService bound");
        nsdManager = (NsdManager) getSystemService(NSD_SERVICE);
        startAdvertising(intent.getIntExtra("port", 0)); // Port of the ServerSocket from CompanionService
        return binder;
    }

    private void startAdvertising(int port) {
        NsdServiceInfo serviceInfo = new NsdServiceInfo();
        serviceInfo.setServiceName(Build.MODEL); // Model of the device
        serviceInfo.setServiceType(SERVICE_TYPE);
        serviceInfo.setPort(port);

        Log.d(TAG, "Service Name: " + Build.MODEL);
        Log.d(TAG, "Service Type: " + SERVICE_TYPE);
        Log.d(TAG, "Service Port: " + port);

        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, regListener);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        nsdManager.unregisterService(regListener);

        Log.d(TAG, "NsdHelperService destroyed");
    }
}
