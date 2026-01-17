package com.avnishkirnalli.tvcompanion;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.pedro.common.ConnectChecker;
import com.pedro.encoder.video.FormatVideoEncoder;
import com.pedro.encoder.video.GetVideoData;
import com.pedro.encoder.video.VideoEncoder;
import com.pedro.rtspserver.server.RtspServer;

import java.nio.ByteBuffer;

public class SurfaceRtspServer implements GetVideoData {

    private final RtspServer rtspServer;
    private final VideoEncoder videoEncoder;
    private boolean isStreaming = false;

    public SurfaceRtspServer(Context context, int port, ConnectChecker connectChecker) {
        // 1. Initialize Server (The code you posted uses this class)
        rtspServer = new RtspServer(connectChecker, port);

        // 2. Initialize Encoder (It will call our callbacks below)
        videoEncoder = new VideoEncoder(this);
    }

    public void start(int width, int height, int fps, int bitrate) {
        // Prepare encoder: H264, default color, iFrameInterval=1s (for low lag)
        boolean prepared = videoEncoder.prepareVideoEncoder(
                width, height, fps, bitrate, 0, 1, FormatVideoEncoder.SURFACE
        );

        if (prepared) {
            videoEncoder.start();
            rtspServer.startServer();
            isStreaming = true;
        } else {
            Log.e("Stream", "Error preparing encoder");
        }
    }

    public void stop() {
        if (isStreaming) {
            videoEncoder.stop();
            rtspServer.stopServer();
            isStreaming = false;
        }
    }

    public int getPort() {
        return rtspServer.getPort();
    }

    public Surface getInputSurface() {
        return videoEncoder.getInputSurface();
    }

    // =========================================================================
    // GetVideoData Implementation (Connecting Encoder -> RTSP Server)
    // =========================================================================

    @Override
    public void onVideoInfo(@NonNull ByteBuffer sps, @Nullable ByteBuffer pps, @Nullable ByteBuffer vps) {
        // Pass the configuration data (SPS/PPS) to the RTSP server so it knows the format
        rtspServer.setVideoInfo(sps, pps, vps);
    }

    @Override
    public void getVideoData(@NonNull ByteBuffer h264Buffer, @NonNull MediaCodec.BufferInfo info) {
        // Pass the actual compressed video frame to the server to send
        rtspServer.sendVideo(h264Buffer, info);
    }

    @Override
    public void onVideoFormat(@NonNull MediaFormat mediaFormat) {
        // Not strictly needed for RTSP streaming, but useful for debugging
    }
}
