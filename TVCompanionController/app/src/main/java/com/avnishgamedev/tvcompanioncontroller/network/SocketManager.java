package com.avnishgamedev.tvcompanioncontroller.network;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

public class SocketManager {
    private Socket socket;
    private DataOutputStream dataOutputStream;
    private DataInputStream dataInputStream;

    private static final int TIMEOUT = 5000; // 5 seconds

    public void connect(String hostAddress, int port) throws IOException {
        if (socket != null && socket.isConnected()) {
            disconnect();
        }
        socket = new Socket();
        socket.connect(new InetSocketAddress(hostAddress, port), TIMEOUT);
        dataOutputStream = new DataOutputStream(socket.getOutputStream());
        dataInputStream = new DataInputStream(socket.getInputStream());
    }

    public void disconnect() throws IOException {
        if (dataOutputStream != null) {
            dataOutputStream.close();
        }
        if (dataInputStream != null) {
            dataInputStream.close();
        }
        if (socket != null) {
            socket.close();
        }
    }

    public DataOutputStream getOutputStream() {
        return dataOutputStream;
    }

    public DataInputStream getInputStream() {
        return dataInputStream;
    }

    public boolean isConnected() {
        return socket != null && socket.isConnected();
    }
}