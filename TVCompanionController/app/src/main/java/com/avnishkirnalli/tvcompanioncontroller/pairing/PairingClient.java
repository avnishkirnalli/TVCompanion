package com.avnishkirnalli.tvcompanioncontroller.pairing;

import com.avnishkirnalli.tvcompanioncontroller.polo.PoloProto;
import com.avnishkirnalli.tvcompanioncontroller.polo.PoloProto.OuterMessage;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.interfaces.RSAPublicKey;

public class PairingClient {

    public interface PairingCallback {
        void onSecretRequested();
        void onSuccess();
        void onError(Exception e);
    }

    private final String host;
    private final int port;
    private final SSLContext sslContext;
    private final KeyStore keyStore;
    private SSLSocket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private PairingCallback callback;
    private volatile boolean isRunning;

    public PairingClient(String host, int port, SSLContext sslContext, KeyStore keyStore) {
        this.host = host;
        this.port = port;
        this.sslContext = sslContext;
        this.keyStore = keyStore;
    }

    public void startPairing(PairingCallback callback) {
        this.callback = callback;
        isRunning = true;
        new Thread(() -> {
            try {
                connect();
                listen();
            } catch (Exception e) {
                if (isRunning) { // Only report error if we weren't intentionally closed
                    callback.onError(e);
                }
            }
        }).start();
    }

    private void connect() throws Exception {
        SSLSocketFactory factory = sslContext.getSocketFactory();
        socket = (SSLSocket) factory.createSocket(host, port);
        // Important: Android TV requires these settings
        socket.setNeedClientAuth(true); 
        socket.setUseClientMode(true);
        socket.setKeepAlive(true);
        socket.setTcpNoDelay(true);
        socket.startHandshake();
        in = new DataInputStream(socket.getInputStream());
        out = new DataOutputStream(socket.getOutputStream());
        sendPairingRequest();
    }

    private void sendPairingRequest() throws IOException {
        PoloProto.PairingRequest pairingRequest = PoloProto.PairingRequest.newBuilder()
                .setServiceName("com.google.android.tv.remote") // Matches standard service name
                .setClientName("TvCompanion")
                .build();

        OuterMessage msg = OuterMessage.newBuilder()
                .setProtocolVersion(2)
                .setStatus(OuterMessage.Status.STATUS_OK)
                .setPairingRequest(pairingRequest)
                .build();

        sendMessage(msg);
    }

    private synchronized void sendMessage(OuterMessage msg) throws IOException {
        if (socket.isClosed()) throw new IOException("Socket is closed");
        byte[] data = msg.toByteArray();
        writeVarInt(data.length);
        out.write(data);
        out.flush();
    }

    private void writeVarInt(int value) throws IOException {
        while ((value & 0xFFFFFF80) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value & 0x7F);
    }

    private int readVarInt() throws IOException {
        int result = 0;
        int shift = 0;
        while (true) {
            int b = in.read();
            if (b == -1) throw new IOException("End of stream");
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
            if (shift > 32) throw new IOException("VarInt too long");
        }
    }

    private void listen() {
        try {
            while (isRunning && !socket.isClosed()) {
                int length = readVarInt();
                if (length < 0) {
                    break;
                }
                byte[] data = new byte[length];
                in.readFully(data);
                OuterMessage msg = OuterMessage.parseFrom(data);
                handleMessage(msg);
            }
        } catch (IOException e) {
            if (isRunning && callback != null) {
                callback.onError(e);
            }
        } finally {
            close();
        }
    }

    private void handleMessage(OuterMessage msg) throws IOException {
        if (msg.getStatus() != OuterMessage.Status.STATUS_OK) {
            throw new IOException("Status Error: " + msg.getStatus());
        }

        OuterMessage.Builder reply = OuterMessage.newBuilder()
                .setProtocolVersion(2)
                .setStatus(OuterMessage.Status.STATUS_OK);

        if (msg.hasPairingRequestAck()) {
            PoloProto.Options.Encoding encoding = PoloProto.Options.Encoding.newBuilder()
                    .setType(PoloProto.Options.Encoding.EncodingType.ENCODING_TYPE_HEXADECIMAL)
                    .setSymbolLength(6)
                    .build();
            PoloProto.Options options = PoloProto.Options.newBuilder()
                    .setPreferredRole(PoloProto.Options.RoleType.ROLE_TYPE_INPUT)
                    .addInputEncodings(encoding)
                    .build();
            reply.setOptions(options);
            sendMessage(reply.build());
        } else if (msg.hasOptions()) {
            PoloProto.Options.Encoding encoding = PoloProto.Options.Encoding.newBuilder()
                    .setType(PoloProto.Options.Encoding.EncodingType.ENCODING_TYPE_HEXADECIMAL)
                    .setSymbolLength(6)
                    .build();
            PoloProto.Configuration config = PoloProto.Configuration.newBuilder()
                    .setClientRole(PoloProto.Options.RoleType.ROLE_TYPE_INPUT)
                    .setEncoding(encoding)
                    .build();
            reply.setConfiguration(config);
            sendMessage(reply.build());
        } else if (msg.hasConfigurationAck()) {
            if (callback != null) callback.onSecretRequested();
        } else if (msg.hasSecretAck()) {
            if (callback != null) callback.onSuccess();
            close();
        }
    }

    public void sendSecret(String code) throws Exception {
        // Fix: Use correct call to get certificate. Ideally should use alias.
        String alias = "tvcompanion-client"; // Hardcoded for now based on KeyStoreManager
        if (!keyStore.containsAlias(alias)) {
             // Fallback to first alias if specific one not found
             java.util.Enumeration<String> aliases = keyStore.aliases();
             if (aliases.hasMoreElements()) {
                 alias = aliases.nextElement();
             } else {
                 throw new Exception("No certificate found in keystore");
             }
        }
        
        Certificate clientCert = keyStore.getCertificate(alias);
        if (clientCert == null) throw new Exception("Certificate not found for alias: " + alias);
        
        RSAPublicKey clientKey = (RSAPublicKey) clientCert.getPublicKey();
        Certificate[] serverChain = socket.getSession().getPeerCertificates();
        RSAPublicKey serverKey = (RSAPublicKey) serverChain[0].getPublicKey();
        byte[] secretBytes = calculateSecret(clientKey, serverKey, code);
        PoloProto.Secret secret = PoloProto.Secret.newBuilder()
                .setSecret(com.google.protobuf.ByteString.copyFrom(secretBytes))
                .build();
        OuterMessage msg = OuterMessage.newBuilder()
                .setProtocolVersion(2)
                .setStatus(OuterMessage.Status.STATUS_OK)
                .setSecret(secret)
                .build();
        sendMessage(msg);
    }

    private byte[] calculateSecret(RSAPublicKey clientKey, RSAPublicKey serverKey, String code) throws Exception {
        if (code.length() < 6) throw new IllegalArgumentException("Code too short");
        String suffix = code.substring(2);
        MessageDigest digest = MessageDigest.getInstance("SHA-26");
        digest.update(getHexBytes(clientKey.getModulus()));
        digest.update(getHexBytes(clientKey.getPublicExponent()));
        digest.update(getHexBytes(serverKey.getModulus()));
        digest.update(getHexBytes(serverKey.getPublicExponent()));
        digest.update(hexToBytes(suffix));
        return digest.digest();
    }

    private byte[] getHexBytes(BigInteger val) {
        String hex = val.toString(16).toUpperCase();
        if (hex.length() % 2 != 0) {
            hex = "0" + hex;
        }
        return hexToBytes(hex);
    }

    private byte[] hexToBytes(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    public void close() {
        isRunning = false;
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            // Ignored
        }
    }
}
