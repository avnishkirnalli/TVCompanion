package com.avnishgamedev.tvcompanion;

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
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class CompanionService extends Service {
    private static final String TAG = "CompanionService";

    private Thread socketThread;
    private ServerSocket serverSocket;

    private boolean isStreaming = false;

    private NsdHelperService nsdHelperService;
    private final ServiceConnection nsdConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            nsdHelperService = ((NsdHelperService.LocalBinder) service).getService();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            nsdHelperService = null;
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundService();
        startCompanionSocket();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (socketThread != null) {
            socketThread.interrupt();
        }
        if (isStreaming) {
            stopService(new Intent(this, ScreenStreamingService.class));
        }
        if (nsdHelperService != null) {
            unbindService(nsdConnection);
        }
        Log.d(TAG, "CompanionService destroyed.");
    }

    private void startForegroundService() {
        final String CHANNEL_ID = "Foreground Service ID";
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "TV Companion Control Service",
                NotificationManager.IMPORTANCE_LOW
        );
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("TV Companion is active")
                .setContentText("Ready for screen sharing commands.")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .build();
        startForeground(1001, notification);
    }

    private void startCompanionSocket() {
        if (socketThread != null) {
            socketThread.interrupt();
        }
        socketThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(0);
                advertiseService(serverSocket.getLocalPort());
                Log.d(TAG, "Control server listening on port: " + serverSocket.getLocalPort());

                while (!Thread.currentThread().isInterrupted()) {
                    Socket clientSocket = serverSocket.accept();
                    Log.d(TAG, "Control client connected: " + clientSocket.getInetAddress());
                    handleClient(clientSocket);
                }
            } catch (IOException e) {
                Log.e(TAG, "Error in server socket", e);
            } finally {
                stopSelf();
            }
        });
        socketThread.start();
    }

    private void handleClient(Socket clientSocket) {
        BroadcastReceiver streamStartedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ScreenStreamingService.ACTION_STREAM_STARTED.equals(intent.getAction())) {
                    int port = intent.getIntExtra(ScreenStreamingService.EXTRA_STREAM_PORT, -1);
                    if (port != -1) {
                        try (DataOutputStream out = new DataOutputStream(clientSocket.getOutputStream())) {
                            out.writeInt(port);
                            Log.d(TAG, "Sent streaming port " + port + " to client.");
                        } catch (IOException e) {
                            Log.e(TAG, "Failed to send port to client.", e);
                        }
                    }
                    unregisterReceiver(this);
                }
            }
        };

        try {
            if (!isStreaming) {
                isStreaming = true;
                IntentFilter filter = new IntentFilter(ScreenStreamingService.ACTION_STREAM_STARTED);
                ContextCompat.registerReceiver(this, streamStartedReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
                startService(new Intent(this, ScreenStreamingService.class));
                Log.d(TAG, "Requested to start ScreenStreamingService.");
            }

            // Keep the connection alive until it's closed by the client or an error occurs.
            // A simple way to do this is to try reading from the input stream.
            // When the client disconnects, read() will throw an exception.
            //noinspection ResultOfMethodCallIgnored
            clientSocket.getInputStream().read();

        } catch (IOException e) {
            Log.d(TAG, "Control client disconnected.");
        } finally {
            if (isStreaming) {
                Log.d(TAG, "Client disconnected, stopping stream.");
                stopService(new Intent(this, ScreenStreamingService.class));
                isStreaming = false;
            }
            try {
                unregisterReceiver(streamStartedReceiver);
            } catch (IllegalArgumentException e) {
                // Receiver might not have been registered if streaming was already active.
            }
            try {
                clientSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing client socket", e);
            }
        }
    }

    private void advertiseService(int port) {
        if (nsdHelperService != null) {
            unbindService(nsdConnection);
        }
        Intent nsdIntent = new Intent(this, NsdHelperService.class);
        nsdIntent.putExtra("port", port);
        bindService(nsdIntent, nsdConnection, Context.BIND_AUTO_CREATE);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
