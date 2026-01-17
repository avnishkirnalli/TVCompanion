package com.avnishkirnalli.tvcompanioncontroller.pairing;

import android.content.Context;

import com.avnishkirnalli.tvcompanioncontroller.remote.RemoteMessageProto;

import javax.net.ssl.SSLContext;

public class TvCompanion {

    private final String host;
    private final KeyStoreManager keyStoreManager;
    private RemoteClient remoteClient;
    private PairingClient pairingClient;

    public TvCompanion(String host, Context context) {
        this.host = host;
        this.keyStoreManager = new KeyStoreManager(context);
    }

    public void pair(PairingClient.PairingCallback callback) {
        try {
            SSLContext sslContext = keyStoreManager.createSSLContext();
            pairingClient = new PairingClient(host, 6467, sslContext, keyStoreManager.getKeyStore());
            pairingClient.startPairing(callback);
        } catch (Exception e) {
            callback.onError(e);
        }
    }

    public void sendPairingSecret(String code) throws Exception {
        if (pairingClient != null) {
            pairingClient.sendSecret(code);
        }
    }

    public void connect() throws Exception {
        SSLContext sslContext = keyStoreManager.createSSLContext();
        remoteClient = new RemoteClient(host, 6466, sslContext);
        remoteClient.connect();
    }

    public void disconnect() {
        if (remoteClient != null) remoteClient.close();
        if (pairingClient != null) pairingClient.close();
    }

    // Commands

    public void dpadUp() {
        if (remoteClient != null) remoteClient.pressKey(RemoteMessageProto.RemoteKeyCode.KEYCODE_DPAD_UP);
    }

    public void dpadDown() {
        if (remoteClient != null) remoteClient.pressKey(RemoteMessageProto.RemoteKeyCode.KEYCODE_DPAD_DOWN);
    }

    public void dpadLeft() {
        if (remoteClient != null) remoteClient.pressKey(RemoteMessageProto.RemoteKeyCode.KEYCODE_DPAD_LEFT);
    }

    public void dpadRight() {
        if (remoteClient != null) remoteClient.pressKey(RemoteMessageProto.RemoteKeyCode.KEYCODE_DPAD_RIGHT);
    }

    public void dpadCenter() {
        if (remoteClient != null) remoteClient.pressKey(RemoteMessageProto.RemoteKeyCode.KEYCODE_DPAD_CENTER);
    }

    public void back() {
        if (remoteClient != null) remoteClient.pressKey(RemoteMessageProto.RemoteKeyCode.KEYCODE_BACK);
    }

    public void home() {
        if (remoteClient != null) remoteClient.pressKey(RemoteMessageProto.RemoteKeyCode.KEYCODE_HOME);
    }

    public void volumeUp() {
        if (remoteClient != null) remoteClient.pressKey(RemoteMessageProto.RemoteKeyCode.KEYCODE_VOLUME_UP);
    }

    public void volumeDown() {
        if (remoteClient != null) remoteClient.pressKey(RemoteMessageProto.RemoteKeyCode.KEYCODE_VOLUME_DOWN);
    }

    public void launchUrl(String url) {
        if (remoteClient != null) remoteClient.launchApp(url);
    }
}
