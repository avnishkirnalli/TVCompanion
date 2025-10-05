package com.avnishgamedev.tvcompanion;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.hardware.display.VirtualDisplay;
import android.media.AudioManager;
import android.media.MediaCodec;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;

public class CompanionService extends Service {
    private static final String TAG = "CompanionService";

    private String serviceName = "TVCompanionService";
    private int localPort = 8080; //0; TEMP

    private Thread socketThread;
    private ServerSocket serverSocket;
    private Socket clientSocket; // Only valid if the client is connected
    private NsdManager.RegistrationListener registrationListener;
    boolean nsdRegistered = false;

    // Streaming
    private ScreenStreamingService screenStreamingService;
    private boolean streamingBound = false;
    private ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            ScreenStreamingService.LocalBinder binder = (ScreenStreamingService.LocalBinder) iBinder;
            screenStreamingService = binder.getService();
            streamingBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            streamingBound = false;
        }
    };

    // MediaProjection
    public static final String ACTION_MEDIA_PROJECTION_PERMISSIONS_RESULT = "com.avnishgamedev.tvcompanion.ACTION_MEDIA_PROJECTION_PERMISSIONS_RESULT";
    private BroadcastReceiver mediaProjectionPermissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_MEDIA_PROJECTION_PERMISSIONS_RESULT.equals(intent.getAction())) {
                int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
                if (resultCode == Activity.RESULT_OK) {
                    Intent permissionData = intent.getParcelableExtra("data");
                    Intent streamIntent = new Intent(CompanionService.this, ScreenStreamingService.class);
                    streamIntent.putExtra("resultCode", resultCode);
                    streamIntent.putExtra("data", permissionData);
                    if (clientSocket != null) {
                        String destIP = clientSocket.getInetAddress().toString().substring(1);
                        streamIntent.putExtra("ip", destIP);
                    }
                    startForegroundService(streamIntent);
                    bindService(streamIntent, connection, BIND_AUTO_CREATE);
                }
            }
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundService();

        initializeRegistrationListener();

        startCompanionSocket();

        registerMediaProjectionPermissionsListener();

        return START_STICKY;
    }

    private void startForegroundService() {
        final String CHANNEL_ID = "Foreground Service ID";
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                CHANNEL_ID,
                NotificationManager.IMPORTANCE_LOW
        );

        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        Notification.Builder notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("TV Companion is active")
                .setContentText("Companion Service is running")
                .setSmallIcon(R.drawable.ic_launcher_foreground);

        startForeground(1001, notification.build());
    }

    public void initializeRegistrationListener() {
        registrationListener = new NsdManager.RegistrationListener() {

            @Override
            public void onServiceRegistered(NsdServiceInfo NsdServiceInfo) {
                // Save the service name. Android may have changed it in order to
                // resolve a conflict, so update the name you initially requested
                // with the name Android actually used.
                serviceName = NsdServiceInfo.getServiceName();
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                // Registration failed! Put debugging code here to determine why.
                Log.e(TAG, "NSD Registration Failed: " + errorCode);
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo arg0) {
                // Service has been unregistered. This only happens when you call
                // NsdManager.unregisterService() and pass in this listener.
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                // Unregistration failed. Put debugging code here to determine why.
                // I don't want this to ever be unregistered
            }
        };
    }

    private void registerNsdService(int port) {
        if (!nsdRegistered) {
            NsdServiceInfo serviceInfo = new NsdServiceInfo();
            serviceInfo.setServiceName(serviceName);
            serviceInfo.setServiceType("_http._tcp.");
            serviceInfo.setPort(port);

            NsdManager nsdManager = (NsdManager) getBaseContext().getSystemService(Context.NSD_SERVICE);
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener);

            nsdRegistered = true;
        }
    }

    private void startCompanionSocket() {
        if (socketThread != null) {
            socketThread.interrupt();
            socketThread = null;
        }
        socketThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(localPort); // 0 = Get next available port
                localPort = serverSocket.getLocalPort();
                Log.d(TAG, "CompanionService listening on Local Port: " + localPort);
                registerNsdService(localPort);
                Log.d(TAG, "ServerSocket initialized! Listening for client connections");
                clientSocket = serverSocket.accept();
                Log.d(TAG, "Client Connected! " + clientSocket.getInetAddress().toString().substring(1)); // Start from 2nd char to remove '/'
                DataInputStream in = new DataInputStream(clientSocket.getInputStream());
                DataOutputStream out = new DataOutputStream(clientSocket.getOutputStream());
                while (true) {
                    String data = in.readUTF();
                    Log.d(TAG, "Received Data from client: " + data);

                    if (data.equals("end")) {
                        break;
                    }

                    String response = processCommand(data);

                    out.writeUTF(response == null ? "noack" : response);
                }
                Log.d(TAG, "Client Disconnected!");
                in.close();
                out.close();
                clientSocket.close();
                serverSocket.close();

                Log.d(TAG, "Restarting ServerSocket");
                startCompanionSocket();
            } catch (IOException e) {
                if (e instanceof EOFException || e instanceof SocketException) {
                    // Client Disconnect or Connection Reset
                    try { serverSocket.close(); } catch (IOException e1) {}
                    Log.d(TAG, "Client Disconnected. Restarting ServerSocket");
                    startCompanionSocket();
                    return;
                }
                throw new RuntimeException(e);
            }
        });
        socketThread.start();
    }

    private String processCommand(String data) {
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        String command = data.split(" ")[0];
        switch (command) {
            case "volume_up":
                audioManager.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_PLAY_SOUND);
                return "ack";
            case "volume_down":
                audioManager.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_PLAY_SOUND);
                return "ack";
            case "volume_set":
                String volumePercentage = data.split(" ")[1];
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (int)((((float)Integer.parseInt(volumePercentage) / 100f)) * ((float)audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))), AudioManager.FLAG_PLAY_SOUND);
                return "ack";
            case "launch_url":
                String url = data.split(" ")[1];
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                browserIntent.addFlags(FLAG_ACTIVITY_NEW_TASK);
                startActivity(browserIntent);
                return "ack";
            case "start_stream":
                requestMediaProjectionPermission();
                return "ack";
            case "stop_stream":
                if (streamingBound) {
                    unbindService(connection);
                    streamingBound = false;
                    return "ack";
                }
        }
        return null;
    }

    void registerMediaProjectionPermissionsListener() {
        IntentFilter filter = new IntentFilter(ACTION_MEDIA_PROJECTION_PERMISSIONS_RESULT);
        ContextCompat.registerReceiver(this, mediaProjectionPermissionReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    void requestMediaProjectionPermission() {
        Intent i = new Intent(this, MediaProjectionSupportActivity.class);
        i.setFlags(FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        unregisterReceiver(mediaProjectionPermissionReceiver);
    }
}
