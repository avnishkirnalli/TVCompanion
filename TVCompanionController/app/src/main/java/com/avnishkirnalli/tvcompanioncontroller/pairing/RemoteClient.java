package com.avnishkirnalli.tvcompanioncontroller.pairing;

import com.avnishkirnalli.tvcompanioncontroller.remote.RemoteMessageProto;
import com.avnishkirnalli.tvcompanioncontroller.remote.RemoteMessageProto.RemoteMessage;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class RemoteClient {

    private final String host;
    private final int port;
    private final SSLContext sslContext;
    private SSLSocket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private boolean isConnected = false;

    public RemoteClient(String host, int port, SSLContext sslContext) {
        this.host = host;
        this.port = port;
        this.sslContext = sslContext;
    }

    public void connect() throws IOException {
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
        isConnected = true;

        // Start request
        RemoteMessage msg = RemoteMessage.newBuilder()
                .setRemoteConfigure(RemoteMessageProto.RemoteConfigure.newBuilder()
                        .setCode1(622)
                        .setDeviceInfo(RemoteMessageProto.RemoteDeviceInfo.newBuilder()
                                .setModel("TVCompanion")
                                .setVendor("AvnishKirnalli")
                                .setUnknown1(1)
                                .setUnknown2("1")
                                .setPackageName("com.avnishkirnalli.tvcompanioncontroller")
                                .setAppVersion("1.0.0")
                                .build())
                        .build())
                .build();
        sendMessage(msg);

        // Start listening thread
        new Thread(this::listen).start();
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

    private void writeVarInt(int value) throws IOException {
        while ((value & 0xFFFFFF80) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value & 0x7F);
    }

    private void listen() {
        try {
            while (isConnected && !socket.isClosed()) {
                int length = readVarInt();
                if (length < 0) break;
                
                byte[] data = new byte[length];
                in.readFully(data);
                
                RemoteMessage msg = RemoteMessage.parseFrom(data);
                handleMessage(msg);
            }
        } catch (IOException e) {
            isConnected = false;
        }
    }

    private void handleMessage(RemoteMessage msg) {
        if (msg.hasRemotePingRequest()) {
            RemoteMessage reply = RemoteMessage.newBuilder()
                    .setRemotePingResponse(RemoteMessageProto.RemotePingResponse.newBuilder()
                            .setVal1(msg.getRemotePingRequest().getVal1())
                            .build())
                    .build();
            try {
                sendMessage(reply);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        // Handle other updates (volume, etc) if needed
    }

    public synchronized void sendMessage(RemoteMessage msg) throws IOException {
        if (!isConnected) throw new IOException("Not connected");
        byte[] data = msg.toByteArray();
        writeVarInt(data.length);
        out.write(data);
        out.flush();
    }

    public void sendKey(RemoteMessageProto.RemoteKeyCode keyCode, RemoteMessageProto.RemoteDirection direction) {
        RemoteMessage msg = RemoteMessage.newBuilder()
                .setRemoteKeyInject(RemoteMessageProto.RemoteKeyInject.newBuilder()
                        .setKeyCode(keyCode)
                        .setDirection(direction)
                        .build())
                .build();
        try {
            sendMessage(msg);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    public void pressKey(RemoteMessageProto.RemoteKeyCode keyCode) {
        sendKey(keyCode, RemoteMessageProto.RemoteDirection.SHORT);
    }

    public void launchApp(String url) {
        RemoteMessage msg = RemoteMessage.newBuilder()
                .setRemoteAppLinkLaunchRequest(RemoteMessageProto.RemoteAppLinkLaunchRequest.newBuilder()
                        .setAppLink(url)
                        .build())
                .build();
        try {
            sendMessage(msg);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    public void close() {
        isConnected = false;
        try {
            if (socket != null) socket.close();
        } catch (IOException e) { }
    }
}
