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
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
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

        encoder = MediaCodec.createEncoderByType("video/avc");
        MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 4000000);  // 4Mbps
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);  // key frame interval in seconds

        // Set the H.264 profile to Baseline for maximum compatibility
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);

        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        Surface inputSurface = encoder.createInputSurface();
        encoder.start();

        VirtualDisplay virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenCapture",
                width,
                height,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface,
                null,
                null);
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
