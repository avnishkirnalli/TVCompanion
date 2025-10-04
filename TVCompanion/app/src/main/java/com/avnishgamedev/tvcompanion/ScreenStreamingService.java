package com.avnishgamedev.tvcompanion;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.Nullable;

// This service will only exist when a stream is active.
public class ScreenStreamingService extends Service {
    private static final String TAG = "ScreenStreamingService";

    public class LocalBinder extends Binder {
        public ScreenStreamingService getService() {
            return ScreenStreamingService.this;
        }
    }

    private final IBinder binder = new LocalBinder();

    // Overlay
    private WindowManager windowManager;
    private View overlayView;

    // MediaProjection
    private MediaProjection mediaProjection;
    private Thread streamThread;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationChannel channel = new NotificationChannel(
                "streaming_channel",
                "Screen Streaming",
                NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(channel);

        Notification notification = new Notification.Builder(this, "streaming_channel")
                .setContentTitle("Screen Streaming Active")
                .setContentText("Streaming your screen")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .build();

        startForeground(1, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        showOverlay();
        startStreaming(intent);
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        hideOverlay();
        stopStreaming();
        return super.onUnbind(intent);
    }

    private void showOverlay() {
        // Inflate custom overlay layout
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

    private void hideOverlay() {
        if (overlayView != null) {
            windowManager.removeView(overlayView);
        }
    }

    private void startStreaming(Intent data) {
        Log.d(TAG, "Starting Stream");

        int resultCode = data.getIntExtra("resultCode", -1);
        Intent permissionData = data.getParcelableExtra("data");

        mediaProjection = getSystemService(MediaProjectionManager.class).getMediaProjection(resultCode, permissionData);
        if (mediaProjection != null) {
            Log.d(TAG, "MediaProjection Initialized!!!!");

            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onCapturedContentResize(int width, int height) {
                    super.onCapturedContentResize(width, height);
                }
            }, null);

            // TODO: Initialize a Surface and mediaProjection.VirtualDisplay

            streamThread = new Thread(() -> {
                // TODO: Send Video frames to other device
            });
        }
    }

    private void stopStreaming() {
        Log.d(TAG, "Stopping Stream");

        streamThread.interrupt();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (mediaProjection != null) {
            mediaProjection.stop();
        }
    }
}
