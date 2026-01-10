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
import android.media.AudioManager;
import android.net.Uri;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;


/**
 * This is the main service of TV Companion which acts as the host.
 * It is responsible for:
 * 1. Opening the ServerSocket and listening for connections.
 * 2. Receiving commands from controller and parsing them.
 * 3. Executing commands from controller.
 * 4. Starting/Stopping other services as needed (ScreenStreamingService)
 * 5. Starting up NsdHelperService to advertise the service.
 */
public class CompanionService extends Service {
    private static final String TAG = "CompanionService";

    // ServerSocket Thread
    private int localPort = 8080; // 0; // Get next available port
    private Thread socketThread;
    private ServerSocket serverSocket;
    private Socket clientSocket; // Only valid if the client is connected

    // NSD (Network Service Discovery)
    private NsdHelperService nsdHelperService; // Should be valid whenever the service is bound to it.
    private final ServiceConnection nsdConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            NsdHelperService.LocalBinder b = (NsdHelperService.LocalBinder) iBinder;
            nsdHelperService = b.getService();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            nsdHelperService = null;
        }
    };

    // Screen Streaming
    private ScreenStreamingService screenStreamingService; // Should be valid whenever the service is bound to it.
    private final ServiceConnection screenStreamingConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            ScreenStreamingService.LocalBinder b = (ScreenStreamingService.LocalBinder) iBinder;
            screenStreamingService = b.getService();
        }
        // The Android system calls this when the connection to the service is unexpectedly lost, such as when the service crashes or is killed. This is NOT called when the client unbinds.
        // Hence, whenever we call unbindService, we set screenStreamingService to null explicitly.
        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            screenStreamingService = null;
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundService();

        startCompanionSocket(); // Sets localPort

        // Create and bind to NsdHelperService
        Intent nsdIntent = new Intent(CompanionService.this, NsdHelperService.class);
        nsdIntent.putExtra("port", localPort);
        bindService(nsdIntent, nsdConnection, Context.BIND_AUTO_CREATE);

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
        String command = data.split(" ")[0];
        switch (command) {
            case "volume_up":
                ((AudioManager) getSystemService(AUDIO_SERVICE)).adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_PLAY_SOUND);
                return "ack";
            case "volume_down":
                ((AudioManager) getSystemService(AUDIO_SERVICE)).adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_PLAY_SOUND);
                return "ack";
            case "volume_set":
                String volumePercentage = data.split(" ")[1];
                ((AudioManager) getSystemService(AUDIO_SERVICE)).setStreamVolume(AudioManager.STREAM_MUSIC, (int)((((float)Integer.parseInt(volumePercentage) / 100f)) * ((float)((AudioManager) getSystemService(AUDIO_SERVICE)).getStreamMaxVolume(AudioManager.STREAM_MUSIC))), AudioManager.FLAG_PLAY_SOUND);
                return "ack";
            case "launch_url":
                String url = data.split(" ")[1];
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                browserIntent.addFlags(FLAG_ACTIVITY_NEW_TASK);
                startActivity(browserIntent);
                return "ack";
            case "start_stream":
                if (screenStreamingService == null) {
                    String streamPort = data.split(" ")[1];
                    Intent streamIntent = new Intent(CompanionService.this, ScreenStreamingService.class);
                    streamIntent.putExtra("client_ip", clientSocket.getInetAddress().toString().substring(1)); // substring to get rid of prefixed "/"
                    streamIntent.putExtra("client_port", Integer.parseInt(streamPort));
                    bindService(streamIntent, screenStreamingConnection, Context.BIND_AUTO_CREATE);
                }
                return "ack";
            case "stop_stream":
                if (screenStreamingService != null)
                    unbindService(screenStreamingConnection);
                screenStreamingService = null;
                return "ack";
            default:
                return "noack";
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // OS destroys "purely bound services" itself
        // when no components are bound to it.
        if (nsdHelperService != null)
            unbindService(nsdConnection);
        if (screenStreamingService != null)
            unbindService(screenStreamingConnection);
    }
}
