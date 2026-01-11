package com.avnishgamedev.tvcompanion;

import static android.app.Activity.RESULT_OK;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ResultReceiver;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import androidx.annotation.Nullable;

/**
 * This service is used for streaming the screen to the controller.
 * It is bound to by the CompanionService, but started after receiving the start_stream command
 * and destroyed after receiving the stop_stream command or when CompanionService is destroyed.
 */
public class ScreenStreamingService extends Service {
    private static final String TAG = "ScreenStreamingService";

    // For Binding
    private final IBinder binder = new ScreenStreamingService.LocalBinder();
    public class LocalBinder extends Binder {
        ScreenStreamingService getService() {
            return ScreenStreamingService.this;
        }
    }
    private interface MediaProjectionPermissionCallback {
        void onSuccess(Intent data);
        void onFailure();
    }

    // MediaProjection API
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "ScreenStreamingService bound");

        getMediaProjectionPermission(
                new MediaProjectionPermissionCallback() {
                    @Override
                    public void onSuccess(Intent data) {
                        createMediaProjectionFromPermissionData(data);
                        createVirtualDisplay();
                        // TODO: Supply a surface to the Virtual Display
                        // TODO: Figure out how to stream the screen to the controller
                    }
                    @Override
                    public void onFailure() {
                        // TODO: Tell the controller that permission wasn't granted
                    }
                }
        );

        return binder;
    }

    private void getMediaProjectionPermission(MediaProjectionPermissionCallback callback) {
        ResultReceiver receiver = new ResultReceiver(new Handler(Looper.getMainLooper())) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                super.onReceiveResult(resultCode, resultData);
                // Check if Permission was granted
                if (resultCode == RESULT_OK) {
                    Log.d(TAG, "MediaProjection Permission Granted");
                    Intent data = resultData.getParcelable("data"); // Contains data gotten from the permission
                    if (data == null) {
                        Log.e(TAG, "Invalid State: MediaProjection Permission data is null! But result code is ok!");
                        return;
                    }
                    callback.onSuccess(data);
                } else {
                    Log.d(TAG, "MediaProjection Permission Denied");
                    callback.onFailure();
                }
            }
        };
        Log.d(TAG, "Starting MediaProjection Permission Activity");
        Intent intent = new Intent(this, MediaProjectionPermissionActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("resultReceiver", receiver);
        startActivity(intent);
    }

    // Called after MediaProjection permission is granted
    private void createMediaProjectionFromPermissionData(Intent data) {
        final MediaProjectionManager mediaProjectionManager = getSystemService(MediaProjectionManager.class);
        mediaProjection = mediaProjectionManager.getMediaProjection(RESULT_OK, data); // RESULT_OK because permission was granted.

        // A callback must be registered, otherwise it crashes
        MediaProjection.Callback callback = new MediaProjection.Callback() {};
        mediaProjection.registerCallback(callback, null);
    }

    private void createVirtualDisplay() {
        // A callback must be registered, otherwise it crashes
        VirtualDisplay.Callback callback = new VirtualDisplay.Callback() {};

        final DisplayMetrics displayMetrics = getActualDisplayMetrics();
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenCapture",
                1280,
                720,
                displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                null, // TODO
                callback,
                null
        );
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // TODO: Release surface
        virtualDisplay.release();
        mediaProjection.stop();
        Log.d(TAG, "ScreenStreamingService destroyed");
    }

    // Helpers
    private DisplayMetrics getActualDisplayMetrics() {
        final WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        final Display display = windowManager.getDefaultDisplay();
        final DisplayMetrics displayMetrics = new DisplayMetrics();
        display.getMetrics(displayMetrics);
        return displayMetrics;
    }
}
