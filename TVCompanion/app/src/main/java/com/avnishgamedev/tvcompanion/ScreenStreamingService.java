package com.avnishgamedev.tvcompanion;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;

public class ScreenStreamingService extends Service {
    private static final String TAG = "ScreenStreamingService";

    private final IBinder binder = new LocalBinder();

    private WindowManager windowManager;
    private View overlayView;
    private boolean isStreaming = false;

    // Actual Streaming
    private ServerSocket socket;
    private Socket clientSocket;

    @Override
    public void onCreate() {
        super.onCreate();

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private void showOverlay() {
        if (!isStreaming) {
            createOverlay();
            isStreaming = true;
        }
    }

    private void hideOverlay() {
        if (isStreaming && overlayView != null) {
            windowManager.removeView(overlayView);
            isStreaming = false;
        }
    }

    private void createOverlay() {
        // Inflate your custom overlay layout
        overlayView = LayoutInflater.from(this).inflate(R.layout.recording_overlay, null);

        // Set up window parameters
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.END;
        params.x = 10;
        params.y = 10;

        windowManager.addView(overlayView, params);
    }

    public int startStreaming() {
        try {
            int port = startServerSocket();
            Log.d(TAG, "ScreenStreamingService listening on Local Port: " + port);
            return port;
        } catch (IOException e) {
            e.printStackTrace();
            return -1;
        }
    }

    private int startServerSocket() throws IOException {
        socket = new ServerSocket(9876); //0); // Choose next available port
        int port = socket.getLocalPort();
        new Thread(() -> {
            try {
                clientSocket = socket.accept();
                Log.d(TAG, "Client Connected!");
                while (!clientSocket.isClosed()) {
                    sendStreamFrame();
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).start();
        return port;
    }

    private void sendStreamFrame() {
        try {
            DataOutputStream out = new DataOutputStream(clientSocket.getOutputStream());
            out.writeUTF("Test Data");
            out.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void stopStreaming() {
        try {
            if (socket != null && !socket.isClosed()) {
                Log.d(TAG, "Stopping Screen Streaming");
                clientSocket.close();
                socket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "ScreenStreamingService being Destroyed");
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public class LocalBinder extends Binder {
        public ScreenStreamingService getService() {
            return ScreenStreamingService.this;
        }
    }
}
