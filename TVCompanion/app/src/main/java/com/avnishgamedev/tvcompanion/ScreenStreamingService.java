package com.avnishgamedev.tvcompanion;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class ScreenStreamingService extends Service {
    private static final int NOTIFICATION_ID = 1008;

    private WindowManager windowManager;
    private View overlayView;
    private boolean isStreaming = false;

    @Override
    public void onCreate() {
        super.onCreate();

        createNotificationChannel();

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            switch (action) {
                case "START_OVERLAY":
                    startForeground(NOTIFICATION_ID, createNotification());
                    showOverlay();
                    break;
                case "STOP_OVERLAY":
                    hideOverlay();
                    stopSelf();
                    break;
            }
        }

        return START_REDELIVER_INTENT;
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                "OVERLAY_CHANNEL",
                "Screen Overlay Service",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Displays overlay indicator while streaming");

        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(channel);
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

    private Notification createNotification() {
        // Create a simple notification for the foreground service
        return new NotificationCompat.Builder(this, "OVERLAY_CHANNEL")
                .setContentTitle("Screen Overlay Active")
                .setContentText("Overlay is currently displayed")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .build();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
