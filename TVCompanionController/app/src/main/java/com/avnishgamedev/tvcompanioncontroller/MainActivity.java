package com.avnishgamedev.tvcompanioncontroller;

import android.os.Bundle;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import com.kunal52.AndroidRemoteTv;
import com.kunal52.AndroidTvListener;
import com.kunal52.exception.PairingException;
import com.kunal52.remote.Remotemessage;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;

import java.security.Security;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // FIX 1: Setup Encryption
        Security.removeProvider("BC");
        Security.addProvider(new BouncyCastleProvider());

        // FIX 2: Run on Background Thread
        new Thread(() -> {
            try {
                // FIX 3: Use App's Internal Storage for the Key file
                File myKeyFile = new File(getFilesDir(), "androidtv.keystore");

                // Pass the custom manager to the remote
                AndroidRemoteTv androidRemoteTv = new AndroidRemoteTv();
                androidRemoteTv.setKeyStoreFile(myKeyFile);

                // Connect
                androidRemoteTv.connect("192.168.1.5", new AndroidTvListener() {
                    @Override
                    public void onSecretRequested() {
                        // 1. Switch to UI thread to show the popup
                        runOnUiThread(() -> {
                            final android.widget.EditText input = new android.widget.EditText(MainActivity.this);
                            input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS); // Force caps for easier typing

                            new android.app.AlertDialog.Builder(MainActivity.this)
                                    .setTitle("Pairing Request")
                                    .setMessage("Enter the code shown on your TV:")
                                    .setView(input)
                                    .setCancelable(false) // Prevent clicking outside to close
                                    .setPositiveButton("Submit", (dialog, which) -> {
                                        String code = input.getText().toString().trim();

                                        // 2. Send the code back on a background thread (Network operation)
                                        new Thread(() -> {
                                            androidRemoteTv.sendSecret(code);
                                        }).start();
                                    })
                                    .show();
                        });
                    }

                    @Override
                    public void onConnected() {
                        Log.d("TV", "Connected!");
                        androidRemoteTv.sendCommand(Remotemessage.RemoteKeyCode.KEYCODE_VOLUME_UP, Remotemessage.RemoteDirection.SHORT);
                        androidRemoteTv.sendCommand(Remotemessage.RemoteKeyCode.KEYCODE_VOLUME_UP, Remotemessage.RemoteDirection.SHORT);
                        androidRemoteTv.sendCommand(Remotemessage.RemoteKeyCode.KEYCODE_VOLUME_UP, Remotemessage.RemoteDirection.SHORT);
                        androidRemoteTv.sendCommand(Remotemessage.RemoteKeyCode.KEYCODE_VOLUME_UP, Remotemessage.RemoteDirection.SHORT);
                        androidRemoteTv.sendCommand(Remotemessage.RemoteKeyCode.KEYCODE_VOLUME_UP, Remotemessage.RemoteDirection.SHORT);
                        androidRemoteTv.sendCommand(Remotemessage.RemoteKeyCode.KEYCODE_VOLUME_UP, Remotemessage.RemoteDirection.SHORT);
                        androidRemoteTv.sendCommand(Remotemessage.RemoteKeyCode.KEYCODE_VOLUME_UP, Remotemessage.RemoteDirection.SHORT);
                        androidRemoteTv.sendCommand(Remotemessage.RemoteKeyCode.KEYCODE_VOLUME_UP, Remotemessage.RemoteDirection.SHORT);
                        androidRemoteTv.sendCommand(Remotemessage.RemoteKeyCode.KEYCODE_VOLUME_UP, Remotemessage.RemoteDirection.SHORT);
                        androidRemoteTv.sendCommand(Remotemessage.RemoteKeyCode.KEYCODE_VOLUME_UP, Remotemessage.RemoteDirection.SHORT);
                    }

                    // ... (Keep other listener methods empty or with logs) ...
                    @Override public void onSessionCreated() {}
                    @Override public void onPaired() {}
                    @Override public void onConnectingToRemote() {}
                    @Override public void onDisconnect() {}
                    @Override public void onError(String error) { Log.e("TV", error); }
                });

            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }
}
