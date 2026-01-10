package com.avnishgamedev.tvcompanion;

import static android.app.Activity.RESULT_OK;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
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
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;

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

    private Socket s;
    private Thread streamThread;

    // MediaProjection API
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;

    // MediaCodec API
    private MediaCodec codec;
    private Surface codecSurface;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "ScreenStreamingService bound");

        getMediaProjectionPermission(
                new MediaProjectionPermissionCallback() {
                    @Override
                    public void onSuccess(Intent data) {
                        // TODO: Workout the flow of MediaProjection to Stream, then implement.
                        createMediaProjectionFromPermissionData(data);
                        createMediaCodec();
                        createVirtualDisplay();
                        startStreaming();
                    }
                    @Override
                    public void onFailure() {
                        // TODO: Tell the controller
                    }
                }
        );

        String IP = intent.getStringExtra("client_ip");
        int port = intent.getIntExtra("client_port", 0);

        Log.d(TAG, "Client IP: " + IP);
        Log.d(TAG, "Client Port: " + port);

        new Thread(() -> {
            try {
                s = new Socket(IP, port);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).start();

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

        MediaProjection.Callback callback = new MediaProjection.Callback() {
            @Override
            public void onCapturedContentResize(int width, int height) {
                super.onCapturedContentResize(width, height);
            }
        };
        mediaProjection.registerCallback(callback, null);
    }

    private void createMediaCodec() {
        try {
            codec = MediaCodec.createEncoderByType("video/avc");

            final DisplayMetrics displayMetrics = getActualDisplayMetrics();
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", displayMetrics.widthPixels, displayMetrics.heightPixels);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 10000);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5);

            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codecSurface = codec.createInputSurface();

            codec.start();
        } catch (IOException e) {
            Log.e(TAG, "Failed to create MediaCodec: " + e);
        }
    }

    private void createVirtualDisplay() {
        VirtualDisplay.Callback callback = new VirtualDisplay.Callback() {
            @Override
            public void onPaused() {
                super.onPaused();
            }
        };

        final DisplayMetrics displayMetrics = getActualDisplayMetrics();
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenCapture",
                displayMetrics.widthPixels,
                displayMetrics.heightPixels,
                displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                codecSurface,
                callback,
                null
        );
    }

    private void startStreaming() {
        streamThread = new Thread(() -> {
            try {
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                OutputStream out = s.getOutputStream();

                while (true) {
                    int index = codec.dequeueOutputBuffer(info, 0);
                    if (index >= 0) {
                        ByteBuffer buffer = codec.getOutputBuffer(index);

                        byte[] data = new byte[info.size];
                        buffer.get(data);

                        out.write(data);
                        out.flush();

                        codec.releaseOutputBuffer(index, false);
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        streamThread.start();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        new Thread(() -> {
            try {
                s.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).start();
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
