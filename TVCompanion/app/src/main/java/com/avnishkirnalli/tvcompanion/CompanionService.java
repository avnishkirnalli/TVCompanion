package com.avnishkirnalli.tvcompanion;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

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
        try {
            if (!isStreaming) {
                isStreaming = true;
                String clientIp = clientSocket.getInetAddress().getHostAddress();

                Intent streamIntent = new Intent(this, ScreenStreamingService.class);
                streamIntent.putExtra(ScreenStreamingService.EXTRA_CLIENT_IP, clientIp);
                startService(streamIntent);

                Log.d(TAG, "Requested to start ScreenStreamingService for client: " + clientIp);
            }

            // Keep the connection alive to detect when the client disconnects.
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
