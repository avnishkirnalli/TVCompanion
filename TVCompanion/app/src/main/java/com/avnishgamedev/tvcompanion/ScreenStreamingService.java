package com.avnishgamedev.tvcompanion;

import static android.app.Activity.RESULT_OK;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ResultReceiver;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.pedro.common.ConnectChecker;

public class ScreenStreamingService extends Service {
    public static final String ACTION_STREAM_STARTED = "com.avnishgamedev.tvcompanion.STREAM_STARTED";
    public static final String ACTION_STREAM_STOPPED = "com.avnishgamedev.tvcompanion.STREAM_STOPPED";
    public static final String EXTRA_STREAM_PORT = "com.avnishgamedev.tvcompanion.EXTRA_STREAM_PORT";
    private static final String TAG = "ScreenStreamingService";
    private static final String CHANNEL_ID = "ScreenStreamingServiceChannel";
    private static final int NOTIFICATION_ID = 1;

    private interface MediaProjectionPermissionCallback {
        void onSuccess(Intent data);
        void onFailure();
    }

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private SurfaceRtspServer surfaceRtspServer;
    private Surface inputSurface;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "ScreenStreamingService starting...");

        getMediaProjectionPermission(
                new MediaProjectionPermissionCallback() {
                    @Override
                    public void onSuccess(Intent data) {
                        createNotificationChannel();
                        Notification notification = new NotificationCompat.Builder(ScreenStreamingService.this, CHANNEL_ID)
                                .setContentTitle("Screen Streaming Service")
                                .setContentText("Streaming screen to controller.")
                                .setSmallIcon(R.mipmap.ic_launcher)
                                .build();

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
                        } else {
                            startForeground(NOTIFICATION_ID, notification);
                        }

                        createMediaProjectionFromPermissionData(data);
                        initializeSurfaceRtspServer();
                        createVirtualDisplay();

                        Intent startedIntent = new Intent(ACTION_STREAM_STARTED);
                        startedIntent.putExtra(EXTRA_STREAM_PORT, surfaceRtspServer.getPort());
                        sendBroadcast(startedIntent);
                        Log.d(TAG, "Stream started on port " + surfaceRtspServer.getPort() + ". Broadcast sent.");
                    }

                    @Override
                    public void onFailure() {
                        Log.e(TAG, "Media projection permission denied. Stopping service.");
                        stopSelf();
                    }
                }
        );

        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (surfaceRtspServer != null) {
            surfaceRtspServer.stop();
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
        }

        sendBroadcast(new Intent(ACTION_STREAM_STOPPED));
        Log.d(TAG, "ScreenStreamingService destroyed and broadcast sent.");
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Screen Streaming Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }

    private void getMediaProjectionPermission(MediaProjectionPermissionCallback callback) {
        ResultReceiver receiver = new ResultReceiver(new Handler(Looper.getMainLooper())) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                super.onReceiveResult(resultCode, resultData);
                if (resultCode == RESULT_OK) {
                    Log.d(TAG, "MediaProjection Permission Granted");
                    Intent data = resultData.getParcelable("data");
                    if (data == null) {
                        Log.e(TAG, "Invalid State: MediaProjection Permission data is null! But result code is ok!");
                        callback.onFailure();
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

    private void createMediaProjectionFromPermissionData(Intent data) {
        final MediaProjectionManager mediaProjectionManager = getSystemService(MediaProjectionManager.class);
        mediaProjection = mediaProjectionManager.getMediaProjection(RESULT_OK, data);
        mediaProjection.registerCallback(new MediaProjection.Callback() {},
                null);
    }

    private void createVirtualDisplay() {
        final DisplayMetrics displayMetrics = getActualDisplayMetrics();
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenCapture",
                displayMetrics.widthPixels,
                displayMetrics.heightPixels,
                displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface,
                new VirtualDisplay.Callback() {},
                null
        );
    }

    private void initializeSurfaceRtspServer() {
        surfaceRtspServer = new SurfaceRtspServer(this, 0, new ConnectChecker() {
            @Override
            public void onConnectionStarted(@NonNull String s) {
            }

            @Override
            public void onConnectionSuccess() {
                Log.d(TAG, "RTSP client connected.");
            }

            @Override
            public void onConnectionFailed(@NonNull String reason) {
                Log.w(TAG, "RTSP connection failed: " + reason);
            }

            @Override
            public void onNewBitrate(long bitrate) {
            }

            @Override
            public void onDisconnect() {
                Log.d(TAG, "RTSP client disconnected. Stopping stream service.");
                stopSelf();
            }

            @Override
            public void onAuthError() {
            }

            @Override
            public void onAuthSuccess() {
            }
        });
        final DisplayMetrics displayMetrics = getActualDisplayMetrics();
        surfaceRtspServer.start(displayMetrics.widthPixels, displayMetrics.heightPixels, 30, 3000 * 1024);
        inputSurface = surfaceRtspServer.getInputSurface();
    }

    private DisplayMetrics getActualDisplayMetrics() {
        final WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        final Display display = windowManager.getDefaultDisplay();
        final DisplayMetrics displayMetrics = new DisplayMetrics();
        display.getMetrics(displayMetrics);
        return displayMetrics;
    }
}
