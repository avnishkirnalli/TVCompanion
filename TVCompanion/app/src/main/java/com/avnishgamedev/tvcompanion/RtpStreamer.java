package com.avnishgamedev.tvcompanion;

import android.media.MediaCodec;
import android.util.Log;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;

public class RtpStreamer {
    private static final String TAG = "RtpStreamer";
    private static final int MTU = 1400; // Maximum Transmission Unit
    private static final int RTP_HEADER_LENGTH = 12;
    private static final int MAX_PAYLOAD_SIZE = MTU - RTP_HEADER_LENGTH;

    private final String destIp;
    private final int destPort;
    private final DatagramSocket udpSocket;

    // H.264 specific
    private static final int NAL_UNIT_TYPE_FU_A = 28;
    private static final byte NAL_UNIT_HEADER_S_BIT = (byte) 0x80; // Start bit
    private static final byte NAL_UNIT_HEADER_E_BIT = (byte) 0x40; // End bit

    // RTP state
    private int sequenceNumber = 0;
    private long timestamp = 0;
    private final long ssrc;
    private final int payloadType = 96; // Dynamic payload type for H.264

    // Frame properties
    private final int clockRate = 90000; // Standard for video
    private final int frameRate;

    private byte[] sps = null;
    private byte[] pps = null;

    public RtpStreamer(String destIp, int destPort, int frameRate, DatagramSocket socket) {
        this.destIp = destIp;
        this.destPort = destPort;
        this.frameRate = frameRate;
        this.udpSocket = socket;
        this.ssrc = new Random().nextLong() & 0xFFFFFFFFL; // Use long and mask to get a positive 32-bit int
    }

    public void processBuffer(ByteBuffer data, MediaCodec.BufferInfo bufferInfo) throws IOException {
        Log.d(TAG, "Processing MediaCodec buffer. Size: " + bufferInfo.size + ", Flags: " + bufferInfo.flags);

        // Increment timestamp for the new frame
        // This is the CRITICAL FIX: Timestamp is incremented once per buffer from MediaCodec
        timestamp += (long) clockRate / frameRate;

        // Check for codec config buffer (SPS/PPS)
        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            extractSpsPps(data, bufferInfo);
            return; // Don't send config buffer as a regular frame
        }

        // Find and send all NAL units in the buffer
        findAndSendNalUnits(data, bufferInfo);
    }

    private void findAndSendNalUnits(ByteBuffer data, MediaCodec.BufferInfo bufferInfo) throws IOException {
        // H.264 NAL units are separated by start codes: 00 00 01 or 00 00 00 01
        byte[] buffer = new byte[bufferInfo.size];
        data.get(buffer);

        int nalUnitStartIndex = -1;

        for (int i = 0; i + 3 < buffer.length; i++) {
            // Find start code
            if (buffer[i] == 0x00 && buffer[i+1] == 0x00 && (buffer[i+2] == 0x01 || (buffer[i+2] == 0x00 && buffer[i+3] == 0x01))) {
                if (nalUnitStartIndex != -1) {
                    // Found a complete NAL unit, send it
                    int nalUnitEndIndex = i;
                    int nalUnitLength = nalUnitEndIndex - nalUnitStartIndex;
                    byte[] nalUnit = Arrays.copyOfRange(buffer, nalUnitStartIndex, nalUnitEndIndex);
                    sendNalUnit(nalUnit, false); // Not the last NAL of the frame yet
                }
                // Found a new start code, mark its beginning
                nalUnitStartIndex = i + (buffer[i+2] == 0x01 ? 3 : 4);
                i += (buffer[i+2] == 0x01 ? 2 : 3); // Skip past the start code
            }
        }

        // Send the last NAL unit in the buffer
        if (nalUnitStartIndex != -1) {
            int nalUnitLength = buffer.length - nalUnitStartIndex;
            byte[] nalUnit = Arrays.copyOfRange(buffer, nalUnitStartIndex, buffer.length);

            // The marker bit should be set on the last packet of the last NAL unit of the access unit (frame).
            boolean isLastNalOfFrame = true;
            sendNalUnit(nalUnit, isLastNalOfFrame);
        } else {
            // If no start codes found, assume the whole buffer is one NAL unit
            sendNalUnit(buffer, true);
        }
    }

    private void sendNalUnit(byte[] nalUnit, boolean isLastNalOfFrame) throws IOException {
        int nalUnitType = nalUnit[0] & 0x1F;

        // Handle SPS/PPS sending before keyframes (IDR frames)
        if (nalUnitType == 5) { // IDR Frame (Keyframe)
            if (sps != null && pps != null) {
                Log.d(TAG, "Sending SPS and PPS before keyframe");
                sendSingleNalUnit(sps, false);
                sendSingleNalUnit(pps, false);
            }
        }

        if (nalUnit.length <= MAX_PAYLOAD_SIZE) {
            sendSingleNalUnit(nalUnit, isLastNalOfFrame);
        } else {
            sendFragmentedNalUnit(nalUnit, isLastNalOfFrame);
        }
    }

    private void sendSingleNalUnit(byte[] nalUnit, boolean isLastPacket) throws IOException {
        sendRtpPacket(nalUnit, isLastPacket);
    }

    private void sendFragmentedNalUnit(byte[] nalUnit, boolean isLastNalOfFrame) throws IOException {
        int maxPayloadSize = MAX_PAYLOAD_SIZE - 2; // Reserve 2 bytes for FU-A headers

        byte originalNalHeader = nalUnit[0];
        int nri = originalNalHeader & 0x60; // Bits 5-6

        int offset = 1; // Skip original NAL unit header
        while (offset < nalUnit.length) {
            int chunkSize = Math.min(maxPayloadSize, nalUnit.length - offset);
            boolean isLastFragment = (offset + chunkSize) >= nalUnit.length;

            byte[] payload = new byte[chunkSize + 2];

            // FU Indicator (1 byte)
            payload[0] = (byte) (nri | NAL_UNIT_TYPE_FU_A);

            // FU Header (1 byte)
            byte fuHeader = (byte) (originalNalHeader & 0x1F); // NAL type
            if (offset == 1) { // First fragment
                fuHeader |= NAL_UNIT_HEADER_S_BIT;
            }
            if (isLastFragment) {
                fuHeader |= NAL_UNIT_HEADER_E_BIT;
            }
            payload[1] = fuHeader;

            System.arraycopy(nalUnit, offset, payload, 2, chunkSize);

            boolean isMarkerSet = isLastNalOfFrame && isLastFragment;
            sendRtpPacket(payload, isMarkerSet);

            offset += chunkSize;
        }
    }

    private void sendRtpPacket(byte[] payload, boolean marker) throws IOException {
        byte[] packet = new byte[RTP_HEADER_LENGTH + payload.length];
        InetAddress destAddr = InetAddress.getByName(destIp);

        // --- Assemble RTP Header ---
        // Version (V=2), Padding (P=0), Extension (X=0), CSRC count (CC=0)
        packet[0] = (byte) 0x80;
        // Marker (M) and Payload Type (PT)
        packet[1] = (byte) ((marker ? 0x80 : 0x00) | (payloadType & 0x7F));
        // Sequence Number
        packet[2] = (byte) (sequenceNumber >> 8);
        packet[3] = (byte) sequenceNumber;
        // Timestamp
        packet[4] = (byte) (timestamp >> 24);
        packet[5] = (byte) (timestamp >> 16);
        packet[6] = (byte) (timestamp >> 8);
        packet[7] = (byte) timestamp;
        // SSRC
        packet[8] = (byte) (ssrc >> 24);
        packet[9] = (byte) (ssrc >> 16);
        packet[10] = (byte) (ssrc >> 8);
        packet[11] = (byte) ssrc;

        // Copy payload
        System.arraycopy(payload, 0, packet, RTP_HEADER_LENGTH, payload.length);

        // Send packet
        udpSocket.send(new DatagramPacket(packet, packet.length, destAddr, destPort));
        Log.d(TAG, "Sent RTP packet: Seq=" + sequenceNumber + ", TS=" + timestamp + ", Size=" + packet.length + ", Marker=" + marker);

        // Increment sequence number
        sequenceNumber = (sequenceNumber + 1) & 0xFFFF;
    }

    private void extractSpsPps(ByteBuffer configBuffer, MediaCodec.BufferInfo bufferInfo) {
        // Typically SPS and PPS are sent together in the config buffer.
        // They are separated by NAL start codes.
        byte[] config = new byte[bufferInfo.size];
        configBuffer.get(config);

        int spsEnd = -1;
        // Find the second start code to separate SPS and PPS
        for (int i = 4; i + 3 < config.length; i++) {
            if (config[i] == 0x00 && config[i+1] == 0x00 && config[i+2] == 0x00 && config[i+3] == 0x01) {
                spsEnd = i;
                break;
            }
        }

        if (spsEnd != -1) {
            // Assumes start code is 4 bytes (00 00 00 01)
            sps = Arrays.copyOfRange(config, 4, spsEnd);
            pps = Arrays.copyOfRange(config, spsEnd + 4, config.length);
            Log.d(TAG, "Extracted SPS (" + sps.length + " bytes) and PPS (" + pps.length + " bytes)");
        }
    }
}
