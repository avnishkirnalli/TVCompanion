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
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.ResultReceiver;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.net.DatagramSocket;
import java.nio.ByteBuffer;

public class ScreenStreamingService extends Service {
    public static final String EXTRA_CLIENT_IP = "com.avnishgamedev.tvcompanion.EXTRA_CLIENT_IP";
    private static final String TAG = "ScreenStreamingService";
    private static final String CHANNEL_ID = "ScreenStreamingServiceChannel";
    private static final int NOTIFICATION_ID = 1;

    private static final String VIDEO_MIME_TYPE = "video/avc";
    private static final int FRAME_RATE = 30;
    private static final int I_FRAME_INTERVAL = 2; // seconds
    private static final int BIT_RATE = 800 * 1024;
    private static final int DEST_PORT = 5005;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private MediaCodec videoEncoder;
    private HandlerThread encoderThread;
    private RtpStreamer rtpStreamer;
    private DatagramSocket udpSocket;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }

        String clientIp = intent.getStringExtra(EXTRA_CLIENT_IP);
        if (clientIp == null) {
            Log.e(TAG, "Client IP not provided. Stopping service.");
            stopSelf();
            return START_NOT_STICKY;
        }

        Log.d(TAG, "ScreenStreamingService starting for client: " + clientIp);

        getMediaProjectionPermission(new MediaProjectionPermissionCallback() {
            @Override
            public void onSuccess(Intent data) {
                createNotificationChannel();
                Notification notification = new NotificationCompat.Builder(ScreenStreamingService.this, CHANNEL_ID)
                        .setContentTitle("Screen Streaming Service")
                        .setContentText("Streaming screen to " + clientIp)
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .build();

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
                } else {
                    startForeground(NOTIFICATION_ID, notification);
                }

                try {
                    startStreaming(data, clientIp);
                } catch (IOException e) {
                    Log.e(TAG, "Failed to start streaming", e);
                    stopSelf();
                }
            }

            @Override
            public void onFailure() {
                Log.e(TAG, "Media projection permission denied. Stopping service.");
                stopSelf();
            }
        });

        return START_NOT_STICKY;
    }

    private void startStreaming(Intent permissionData, String clientIp) throws IOException {
        createMediaProjection(permissionData);

        udpSocket = new DatagramSocket();
        rtpStreamer = new RtpStreamer(clientIp, DEST_PORT, FRAME_RATE, udpSocket);

        configureEncoder();

        createVirtualDisplay();

        videoEncoder.start();
        Log.d(TAG, "MediaCodec started. Streaming to " + clientIp + ":" + DEST_PORT);
    }

    private void configureEncoder() throws IOException {
        DisplayMetrics displayMetrics = getActualDisplayMetrics();
        int width = displayMetrics.widthPixels;
        int height = displayMetrics.heightPixels;

        MediaFormat format = MediaFormat.createVideoFormat(VIDEO_MIME_TYPE, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);
        format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);

        videoEncoder = MediaCodec.createEncoderByType(VIDEO_MIME_TYPE);
        
        encoderThread = new HandlerThread("VideoEncoder");
        encoderThread.start();
        
        videoEncoder.setCallback(new MediaCodec.Callback() {
            @Override
            public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
                // Not used when encoding from a Surface
            }

            @Override
            public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
                try {
                    ByteBuffer outputBuffer = codec.getOutputBuffer(index);
                    if (outputBuffer != null && rtpStreamer != null) {
                        rtpStreamer.processBuffer(outputBuffer, info);
                    }
                    codec.releaseOutputBuffer(index, false);
                } catch (IOException e) {
                    Log.e(TAG, "Failed to send RTP packet", e);
                }
            }

            @Override
            public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
                Log.e(TAG, "MediaCodec error: " + e.getMessage());
                stopSelf();
            }

            @Override
            public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
                Log.i(TAG, "MediaCodec output format changed: " + format);
            }
        }, new Handler(encoderThread.getLooper()));

        videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    }

    private void createVirtualDisplay() {
        DisplayMetrics displayMetrics = getActualDisplayMetrics();
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenCapture",
                displayMetrics.widthPixels,
                displayMetrics.heightPixels,
                displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                videoEncoder.createInputSurface(),
                null,
                null
        );
    }


    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "ScreenStreamingService destroyed.");
        if (udpSocket != null) {
            udpSocket.close();
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
        }
        if (videoEncoder != null) {
            videoEncoder.stop();
            videoEncoder.release();
        }
        if (encoderThread != null) {
            encoderThread.quitSafely();
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
        }
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
    
    private interface MediaProjectionPermissionCallback {
        void onSuccess(Intent data);
        void onFailure();
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

    private void createMediaProjection(Intent data) {
        final MediaProjectionManager mediaProjectionManager = getSystemService(MediaProjectionManager.class);
        mediaProjection = mediaProjectionManager.getMediaProjection(RESULT_OK, data);
        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                super.onStop();
                Log.w(TAG, "MediaProjection stopped. Stopping service.");
                stopSelf();
            }
        }, null);
    }

    private DisplayMetrics getActualDisplayMetrics() {
        final WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        final Display display = windowManager.getDefaultDisplay();
        final DisplayMetrics displayMetrics = new DisplayMetrics();
        display.getRealMetrics(displayMetrics);
        return displayMetrics;
    }
}
