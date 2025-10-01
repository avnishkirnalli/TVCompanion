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

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        showOverlay();
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        hideOverlay();
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

    private void startStreaming() {
        Log.d(TAG, "Starting Stream");
    }

    private void stopStreaming() {
        Log.d(TAG, "Stopping Stream");
    }
}
