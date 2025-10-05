package com.avnishgamedev.tvcompanion;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.net.DatagramSocket;
import java.nio.ByteBuffer;

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
    private MediaCodec encoder;
    private DatagramSocket udpSocket;
    private RtpStreamer rtpStreamer;

    private Handler mHandler;
    private MediaProjection.Callback mMediaProjectionCallback;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Create notification channel (required for Android 8.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "streaming_channel",
                    "Screen Streaming",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }

        // Build notification
        Notification notification;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification = new Notification.Builder(this, "streaming_channel")
                    .setContentTitle("Screen Streaming Active")
                    .setContentText("Streaming your screen")
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .build();
        } else {
            // For Android 7.1 and below
            notification = new Notification.Builder(this)
                    .setContentTitle("Screen Streaming Active")
                    .setContentText("Streaming your screen")
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setPriority(Notification.PRIORITY_LOW)
                    .build();
        }

        // Start foreground with proper API level handling
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ (API 29+) - requires foreground service type
            startForeground(1, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            // Android 9 and below - no service type parameter
            startForeground(1, notification);
        }

        return START_STICKY;
    }


    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        showOverlay();
        startStreaming(intent);
        Log.d(TAG, "Service bound");
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        hideOverlay();
        stopStreaming();
        stopSelf();
        Log.d(TAG, "Service unbound");
        return super.onUnbind(intent);
    }

    private void showOverlay() {
        // Check if we have permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Log.w(TAG, "No overlay permission - skipping overlay");
                return;
            }
        }

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

    private void createHandlerThread() {
        HandlerThread handlerThread = new HandlerThread("MediaProjectionCallback");
        handlerThread.start();
        mHandler = new Handler(handlerThread.getLooper());
    }

    private void startStreaming(Intent data) {
        Log.d(TAG, "Starting Stream");

        int resultCode = data.getIntExtra("resultCode", -1);
        Intent permissionData = data.getParcelableExtra("data");

        MediaProjectionManager projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mediaProjection = projectionManager.getMediaProjection(resultCode, permissionData);

        if (mediaProjection != null) {
            createHandlerThread();
            mMediaProjectionCallback = new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Log.w(TAG, "MediaProjection session stopped by user.");
                    // This is critical. Trigger a clean shutdown.
                    stopStreaming();
                    stopSelf(); // Stops the service itself.
                }
            };
            mediaProjection.registerCallback(mMediaProjectionCallback, mHandler);

            try {
                setupEncoderAndVirtualDisplay();

                // Initialize socket and streamer
                udpSocket = new DatagramSocket();
                rtpStreamer = new RtpStreamer(data.getStringExtra("ip"), 5005, 30, udpSocket);

                streamThread = new Thread(() -> {
                    MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                    while (!Thread.currentThread().isInterrupted()) {
                        try {
                            int outputBufferId = encoder.dequeueOutputBuffer(bufferInfo, 10000);
                            if (outputBufferId >= 0) {
                                ByteBuffer encodedData = encoder.getOutputBuffer(outputBufferId);
                                if (encodedData != null) {
                                    // Position the buffer for reading
                                    encodedData.position(bufferInfo.offset);
                                    encodedData.limit(bufferInfo.offset + bufferInfo.size);

                                    try {
                                        // Let the streamer handle all the complex logic
                                        rtpStreamer.processBuffer(encodedData, bufferInfo);
                                    } catch (IOException e) {
                                        Log.e(TAG, "Error sending RTP data", e);
                                        break; // Stop streaming on error
                                    }
                                }
                                encoder.releaseOutputBuffer(outputBufferId, false);
                            } else if (outputBufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                                // No output buffer available yet
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                });
                streamThread.start();
            } catch (Exception e) {
                Log.e(TAG, "Failed to start streaming", e);
                stopSelf(); // Stop the service if setup fails
            }
        }
    }

    private void setupEncoderAndVirtualDisplay() throws IOException {
        int width = 1280;
        int height = 720;
        int dpi = Resources.getSystem().getDisplayMetrics().densityDpi;

        try {
            // Try to find a working encoder
            encoder = findWorkingEncoder();

            if (encoder == null) {
                Log.e(TAG, "No suitable H.264 encoder found");
                throw new IOException("No H.264 encoder available");
            }

            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);

            // Conservative settings for TV boxes
            format.setInteger(MediaFormat.KEY_BIT_RATE, 2000000);  // Reduced to 2Mbps
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);  // Keyframe every 2 seconds

            // Use Baseline profile for maximum compatibility with Amlogic
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31);

            // Additional compatibility settings
            format.setInteger(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1000000); // 1 second
            format.setInteger(MediaFormat.KEY_COMPLEXITY, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);

            Log.d(TAG, "Configuring encoder: " + encoder.getName() + " with format: " + format);

            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            Surface inputSurface = encoder.createInputSurface();
            encoder.start();

            Log.d(TAG, "Encoder started successfully");

            VirtualDisplay virtualDisplay = mediaProjection.createVirtualDisplay(
                    "ScreenCapture",
                    width,
                    height,
                    dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    inputSurface,
                    null,
                    null);

            Log.d(TAG, "Virtual display created successfully");

        } catch (IllegalStateException e) {
            Log.e(TAG, "Encoder configuration failed - likely unsupported format", e);
            throw new IOException("Encoder configuration not supported on this device", e);
        } catch (Exception e) {
            Log.e(TAG, "Failed to setup encoder", e);
            throw new IOException("Failed to initialize encoder", e);
        }
    }

    private MediaCodec findWorkingEncoder() {
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        MediaCodecInfo[] codecInfos = codecList.getCodecInfos();

        String mimeType = MediaFormat.MIMETYPE_VIDEO_AVC;

        // First, try to find hardware encoders
        for (MediaCodecInfo codecInfo : codecInfos) {
            if (!codecInfo.isEncoder()) {
                continue;
            }

            // Skip software encoders in first pass
            if (codecInfo.getName().toLowerCase().contains("google") ||
                    codecInfo.getName().toLowerCase().contains("sw")) {
                continue;
            }

            String[] types = codecInfo.getSupportedTypes();
            for (String type : types) {
                if (type.equalsIgnoreCase(mimeType)) {
                    MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(mimeType);

                    // Check if it supports surface input
                    for (int colorFormat : capabilities.colorFormats) {
                        if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) {
                            // Verify it supports baseline profile
                            if (supportsBaselineProfile(capabilities)) {
                                try {
                                    MediaCodec codec = MediaCodec.createByCodecName(codecInfo.getName());
                                    Log.d(TAG, "Selected hardware encoder: " + codecInfo.getName());
                                    return codec;
                                } catch (IOException e) {
                                    Log.w(TAG, "Failed to create encoder: " + codecInfo.getName(), e);
                                }
                            }
                        }
                    }
                }
            }
        }

        // Fallback: try any encoder (including software)
        Log.w(TAG, "No hardware encoder found, trying software encoder");
        for (MediaCodecInfo codecInfo : codecInfos) {
            if (!codecInfo.isEncoder()) {
                continue;
            }

            String[] types = codecInfo.getSupportedTypes();
            for (String type : types) {
                if (type.equalsIgnoreCase(mimeType)) {
                    MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(mimeType);

                    for (int colorFormat : capabilities.colorFormats) {
                        if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) {
                            try {
                                MediaCodec codec = MediaCodec.createByCodecName(codecInfo.getName());
                                Log.d(TAG, "Selected fallback encoder: " + codecInfo.getName());
                                return codec;
                            } catch (IOException e) {
                                Log.w(TAG, "Failed to create fallback encoder: " + codecInfo.getName(), e);
                            }
                        }
                    }
                }
            }
        }

        // Last resort: try default encoder
        try {
            MediaCodec codec = MediaCodec.createEncoderByType(mimeType);
            Log.d(TAG, "Using default encoder: " + codec.getName());
            return codec;
        } catch (IOException e) {
            Log.e(TAG, "Failed to create any encoder", e);
            return null;
        }
    }

    private boolean supportsBaselineProfile(MediaCodecInfo.CodecCapabilities capabilities) {
        MediaCodecInfo.CodecProfileLevel[] profileLevels = capabilities.profileLevels;

        if (profileLevels == null || profileLevels.length == 0) {
            return true; // Assume support if not specified
        }

        for (MediaCodecInfo.CodecProfileLevel profileLevel : profileLevels) {
            if (profileLevel.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline) {
                return true;
            }
        }

        return false;
    }

    private void stopStreaming() {
        Log.d(TAG, "Stopping Stream");
        if (streamThread != null) {
            streamThread.interrupt();
        }
        if (mHandler != null) {
            mHandler.getLooper().quitSafely();
            mHandler = null;
        }
        if (udpSocket != null) {
            udpSocket.close();
            udpSocket = null;
        }
        if (encoder != null) {
            encoder.stop();
            encoder.release();
            encoder = null;
        }
        if (mediaProjection != null) {
            if (mMediaProjectionCallback != null) {
                mediaProjection.unregisterCallback(mMediaProjectionCallback);
                mMediaProjectionCallback = null;
            }
            mediaProjection.stop();
            mediaProjection = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopStreaming(); // Ensure thread is stopped

        Log.d(TAG, "Service destroyed");
    }
}
