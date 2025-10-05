package com.avnishgamedev.tvcompanioncontroller;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.ByteBuffer;
import java.util.ArrayList;

public class RTPReceiver extends Thread {
    private static final String TAG = "RTPReceiver";
    private static final int RTP_PORT = 5005;
    private static final int MAX_PACKET_SIZE = 65536;
    private static final int MAX_NAL_SIZE = 1024 * 1024; // 1MB for large frames

    private DatagramSocket socket;
    private MediaCodec decoder;
    private Surface surface;
    private volatile boolean running = true;

    // H.264 NAL unit reassembly
    private ArrayList<byte[]> fuaFragments = new ArrayList<>();
    private int fuaTotalSize = 0;

    public RTPReceiver(Surface surface) throws IOException {
        this.surface = surface;
        socket = new DatagramSocket(RTP_PORT);
        socket.setReceiveBufferSize(2 * 1024 * 1024); // 2MB buffer
        setupDecoder();
    }

    private void setupDecoder() throws IOException {
        decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);

        // Use actual stream resolution: 1280x720
        MediaFormat format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                1280,
                720
        );

        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_NAL_SIZE);
        format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);

        decoder.configure(format, surface, null, 0);
        decoder.start();

        Log.d(TAG, "MediaCodec decoder started for 1280x720");
    }

    @Override
    public void run() {
        byte[] buffer = new byte[MAX_PACKET_SIZE];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

        Log.d(TAG, "RTP Receiver started on port " + RTP_PORT);

        while (running) {
            try {
                socket.receive(packet);
                processRTPPacket(packet.getData(), packet.getLength());
            } catch (IOException e) {
                if (running) {
                    Log.e(TAG, "Socket error", e);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error processing packet", e);
            }
        }

        Log.d(TAG, "RTP Receiver stopped");
    }

    private void processRTPPacket(byte[] data, int length) {
        if (length < 12) return; // Minimum RTP header size

        try {
            // Parse RTP header
            int version = (data[0] >> 6) & 0x03;
            boolean padding = ((data[0] >> 5) & 0x01) == 1;
            boolean extension = ((data[0] >> 4) & 0x01) == 1;
            int csrcCount = data[0] & 0x0F;

            boolean marker = ((data[1] >> 7) & 0x01) == 1;
            int payloadType = data[1] & 0x7F;

            int sequenceNumber = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
            long timestamp = ((data[4] & 0xFFL) << 24) | ((data[5] & 0xFFL) << 16)
                    | ((data[6] & 0xFFL) << 8) | (data[7] & 0xFFL);

            int headerLength = 12 + (csrcCount * 4);
            if (extension) {
                int extLength = ((data[headerLength + 2] & 0xFF) << 8) | (data[headerLength + 3] & 0xFF);
                headerLength += 4 + (extLength * 4);
            }

            // Extract H.264 payload
            int payloadLength = length - headerLength;
            if (padding) {
                payloadLength -= (data[length - 1] & 0xFF);
            }

            if (payloadLength <= 0 || headerLength + payloadLength > length) {
                return;
            }

            byte[] payload = new byte[payloadLength];
            System.arraycopy(data, headerLength, payload, 0, payloadLength);

            processH264Payload(payload, marker, timestamp);
        } catch (Exception e) {
            Log.e(TAG, "Error in processRTPPacket", e);
        }
    }

    private void processH264Payload(byte[] payload, boolean marker, long timestamp) {
        if (payload.length == 0) return;

        try {
            int nalUnitType = payload[0] & 0x1F;

            if (nalUnitType >= 1 && nalUnitType <= 23) {
                // Single NAL unit
                feedToDecoder(payload, timestamp);
            } else if (nalUnitType == 28) {
                // FU-A (Fragmentation Unit)
                handleFUA(payload, marker, timestamp);
            } else if (nalUnitType == 24) {
                // STAP-A (Single Time Aggregation Packet)
                handleSTAPA(payload, timestamp);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in processH264Payload", e);
        }
    }

    private void handleFUA(byte[] payload, boolean marker, long timestamp) {
        if (payload.length < 2) return;

        try {
            byte fuIndicator = payload[0];
            byte fuHeader = payload[1];

            boolean start = ((fuHeader >> 7) & 0x01) == 1;
            boolean end = ((fuHeader >> 6) & 0x01) == 1;
            int nalType = fuHeader & 0x1F;

            if (start) {
                // Start of fragmented NAL unit - clear previous fragments
                fuaFragments.clear();
                fuaTotalSize = 0;

                // Add reconstructed NAL header
                byte reconstructedNalHeader = (byte)((fuIndicator & 0xE0) | nalType);
                byte[] headerFragment = new byte[1];
                headerFragment[0] = reconstructedNalHeader;
                fuaFragments.add(headerFragment);
                fuaTotalSize += 1;
            }

            // Add fragment data (skip FU indicator and FU header)
            if (payload.length > 2) {
                byte[] fragment = new byte[payload.length - 2];
                System.arraycopy(payload, 2, fragment, 0, payload.length - 2);
                fuaFragments.add(fragment);
                fuaTotalSize += fragment.length;
            }

            if (end && marker) {
                // Complete NAL unit - reassemble all fragments
                if (fuaTotalSize > 0 && fuaTotalSize < MAX_NAL_SIZE) {
                    byte[] completeNal = new byte[fuaTotalSize];
                    int offset = 0;

                    for (byte[] fragment : fuaFragments) {
                        System.arraycopy(fragment, 0, completeNal, offset, fragment.length);
                        offset += fragment.length;
                    }

                    feedToDecoder(completeNal, timestamp);
                } else {
                    Log.w(TAG, "NAL unit too large or invalid: " + fuaTotalSize + " bytes");
                }

                // Clear fragments
                fuaFragments.clear();
                fuaTotalSize = 0;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in handleFUA", e);
            fuaFragments.clear();
            fuaTotalSize = 0;
        }
    }

    private void handleSTAPA(byte[] payload, long timestamp) {
        try {
            int offset = 1; // Skip STAP-A NAL header

            while (offset < payload.length - 2) {
                int nalSize = ((payload[offset] & 0xFF) << 8) | (payload[offset + 1] & 0xFF);
                offset += 2;

                if (offset + nalSize <= payload.length && nalSize > 0) {
                    byte[] nal = new byte[nalSize];
                    System.arraycopy(payload, offset, nal, 0, nalSize);
                    feedToDecoder(nal, timestamp);
                    offset += nalSize;
                } else {
                    break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in handleSTAPA", e);
        }
    }

    private void feedToDecoder(byte[] nalUnit, long timestamp) {
        if (nalUnit == null || nalUnit.length == 0) return;

        try {
            int inputIndex = decoder.dequeueInputBuffer(10000);
            if (inputIndex >= 0) {
                ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
                if (inputBuffer != null) {
                    inputBuffer.clear();

                    // Check if buffer has enough space
                    if (inputBuffer.remaining() >= nalUnit.length + 4) {
                        // Add start code
                        inputBuffer.put(new byte[]{0x00, 0x00, 0x00, 0x01});
                        inputBuffer.put(nalUnit);

                        decoder.queueInputBuffer(inputIndex, 0, nalUnit.length + 4,
                                timestamp, 0);
                    } else {
                        Log.w(TAG, "Input buffer too small for NAL unit: " + nalUnit.length);
                    }
                }

                // Render output
                MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                int outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 0);

                while (outputIndex >= 0) {
                    decoder.releaseOutputBuffer(outputIndex, true);
                    outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 0);
                }
            }
        } catch (IllegalStateException e) {
            Log.e(TAG, "Decoder in invalid state", e);
        } catch (Exception e) {
            Log.e(TAG, "Error feeding to decoder", e);
        }
    }

    public void shutdown() {
        running = false;

        // Close socket first to interrupt receive()
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }

        // Wait for thread to finish
        try {
            join(1000);
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted while waiting for thread to finish", e);
        }

        // Release decoder
        if (decoder != null) {
            try {
                decoder.stop();
                decoder.release();
                decoder = null;
            } catch (Exception e) {
                Log.e(TAG, "Error releasing decoder", e);
            }
        }

        // Clear fragments
        fuaFragments.clear();
        fuaTotalSize = 0;

        Log.d(TAG, "RTP Receiver shutdown complete");
    }
}
