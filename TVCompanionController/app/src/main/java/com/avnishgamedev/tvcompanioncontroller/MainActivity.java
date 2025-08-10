package com.avnishgamedev.tvcompanioncontroller;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final String SERVICE_TYPE = "_http._tcp.";

    NsdManager nsdManager;
    NsdManager.DiscoveryListener discoveryListener;
    NsdManager.ResolveListener resolveListener;
    NsdServiceInfo connectedService;
    Socket socket;
    DataInputStream in;
    DataOutputStream out;

    // Widgets
    FrameLayout loadingLayout;
    Button btnVolumeUp;
    Button btnVolumeDown;
    EditText etVolume;
    Button btnSetVolume;
    Button btnLaunchUrl;
    EditText etUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        nsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);

        initializeDiscoveryListener();
        initializeResolveListener();

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);

        // Widgets

        loadingLayout = findViewById(R.id.loadingLayout);

        btnVolumeUp = findViewById(R.id.btnVolumeUp);
        btnVolumeUp.setOnClickListener(v -> sendCommand("volume_up"));

        btnVolumeDown = findViewById(R.id.btnVolumeDown);
        btnVolumeDown.setOnClickListener(v -> sendCommand("volume_down"));

        etVolume = findViewById(R.id.etVolume);

        btnSetVolume = findViewById(R.id.btnSetVolume);
        btnSetVolume.setOnClickListener(v -> sendCommand("volume_set " + etVolume.getText().toString()));

        etUrl = findViewById(R.id.etUrl);

        btnLaunchUrl = findViewById(R.id.btnLaunchUrl);
        btnLaunchUrl.setOnClickListener(v -> sendCommand("launch_url " + etUrl.getText().toString()));
    }

    private void initializeDiscoveryListener() {
        // Instantiate a new DiscoveryListener
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String regType) {
                Log.d(TAG, "Service discovery started");
            }

            @Override
            public void onServiceFound(NsdServiceInfo service) {
                Log.d(TAG, "Service discovery success" + service);
                if (!service.getServiceType().equals(SERVICE_TYPE)) {
                    // Service type is the string containing the protocol and
                    // transport layer for this service.
                    Log.d(TAG, "Unknown Service Type: " + service.getServiceType());
                } else if(service.getServiceName().contains("TVCompanionService")) {
                    nsdManager.resolveService(service, resolveListener);
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo service) {
                // When the network service is no longer available.
                // Internal bookkeeping code goes here.
                Log.e(TAG, "service lost: " + service);
                if (connectedService != null) {
                    if (connectedService.getServiceName().equals(service.getServiceName())) {
                        connectedService = null;
                        setLoading(true);
                        if (socket != null) {
                            try {
                                socket.close();
                                in.close();
                                out.close();
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    }
                }
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                Log.i(TAG, "Discovery stopped: " + serviceType);
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Discovery failed: Error code:" + errorCode);
                nsdManager.stopServiceDiscovery(this);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Discovery failed: Error code:" + errorCode);
                nsdManager.stopServiceDiscovery(this);
            }
        };
    }

    private void initializeResolveListener() {
        resolveListener = new NsdManager.ResolveListener() {

            @Override
            public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                // Called when the resolve fails. Use the error code to debug.
                Log.e(TAG, "Resolve failed: " + errorCode);
            }

            @Override
            public void onServiceResolved(NsdServiceInfo serviceInfo) {
                Log.e(TAG, "Resolve Succeeded. " + serviceInfo);

                connectedService = serviceInfo;
                connectToServer();
            }
        };
    }

    private void connectToServer() {
        try {
            InetAddress host = connectedService.getHost();
            int port = connectedService.getPort();
            Log.d(TAG, "Attempting to connect to host: " + host + ", port: " + port);
            socket = new Socket(host, port);
            in = new DataInputStream(socket.getInputStream());
            out = new DataOutputStream(socket.getOutputStream());
            Log.d(TAG, "Connected to server");
            setLoading(false);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void sendCommand(String command) {
        if (socket != null) {
            new Thread(() -> {
                try {
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    out.writeUTF(command);
                    out.flush();
                    String response = in.readUTF();
                    if (response.equals("ack")) {
                        toastOnUiThread("Acknowledged");
                    } else if (response.equals("noack")) {
                        toastOnUiThread("Not Acknowledged");
                    } else {
                        toastOnUiThread("Unknown response: " + response);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }).start();
        }
    }

    private void toastOnUiThread(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    private void setLoading(boolean state) {
        runOnUiThread(() -> loadingLayout.setVisibility(state ? View.VISIBLE : View.GONE));
    }
}