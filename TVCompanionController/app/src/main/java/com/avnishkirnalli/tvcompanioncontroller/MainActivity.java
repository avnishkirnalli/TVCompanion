package com.avnishkirnalli.tvcompanioncontroller;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.avnishkirnalli.tvcompanioncontroller.model.DiscoveredDevice;
import com.avnishkirnalli.tvcompanioncontroller.model.SavedUrl;
import com.avnishkirnalli.tvcompanioncontroller.network.NsdHelper;
import com.avnishkirnalli.tvcompanioncontroller.pairing.PairingClient;
import com.avnishkirnalli.tvcompanioncontroller.pairing.TvCompanion;
import com.avnishkirnalli.tvcompanioncontroller.ui.DeviceAdapter;
import com.google.android.material.appbar.MaterialToolbar;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.net.Socket;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Security;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements NsdHelper.NsdListener, DeviceAdapter.OnDeviceClickListener, SurfaceHolder.Callback {

    private static final String TAG = "MainActivity";
    private static final String PREFS_NAME = "TVCompanionPrefs";
    private static final String KEY_DEVICE_NAME = "device_name"; // The NSD Service Name
    private static final String KEY_SAVED_URLS = "saved_urls";

    private static final String KEYSTORE_FILE = "tv_companion_keystore.bks";
    private static final String KEYSTORE_ALIAS = "tvcompanion-client";
    private static final String KEYSTORE_PASSWORD = "password";

    private NsdHelper nsdHelper;
    private TvCompanion tvCompanion;
    private KeyStore keyStore;

    private DeviceAdapter deviceAdapter;
    private LinearLayout deviceDiscoveryLayout;
    private NestedScrollView remoteControlLayout;
    private TextView discoveryStatusText;

    private String savedDeviceName;
    private boolean isConnectingToSavedDevice = false;

    private SurfaceView surfaceView;
    private RTPReceiver rtpReceiver;
    private Socket clientSocket;
    private DiscoveredDevice streamingDevice;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        surfaceView = findViewById(R.id.surfaceView);
        surfaceView.getHolder().addCallback(this);

        Security.removeProvider("BC");
        Security.addProvider(new BouncyCastleProvider());

        try {
            initKeyStore();
        } catch (Exception e) {
            Toast.makeText(this, "Error: Could not initialize security.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        deviceDiscoveryLayout = findViewById(R.id.deviceDiscoveryLayout);
        remoteControlLayout = findViewById(R.id.remoteControlLayout);
        discoveryStatusText = findViewById(R.id.discoveryStatusText);

        setupRecyclerView();
        setupRemoteButtons();

        findViewById(R.id.scanButton).setOnClickListener(v -> disconnectAndReset(true));

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        savedDeviceName = prefs.getString(KEY_DEVICE_NAME, null);

        if (savedDeviceName != null) {
            isConnectingToSavedDevice = true;
        }
        startDiscovery();
    }

    private void initKeyStore() throws Exception {
        keyStore = KeyStore.getInstance("BKS");
        File keyStoreFile = new File(getFilesDir(), KEYSTORE_FILE);

        if (keyStoreFile.exists()) {
            try (FileInputStream fis = new FileInputStream(keyStoreFile)) {
                keyStore.load(fis, KEYSTORE_PASSWORD.toCharArray());
            }
        } else {
            // Generate a new key pair and self-signed certificate
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048);
            KeyPair keyPair = keyPairGenerator.generateKeyPair();
            PublicKey publicKey = keyPair.getPublic();
            PrivateKey privateKey = keyPair.getPrivate();

            X500Name issuer = new X500Name("CN=TVCompanionController");
            BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
            Date notBefore = new Date();
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.YEAR, 10);
            Date notAfter = cal.getTime();

            X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                    issuer, serial, notBefore, notAfter, issuer, publicKey);

            // Add extensions for modern compatibility
            certBuilder.addExtension(Extension.basicConstraints, false, new BasicConstraints(false));
            certBuilder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));

            ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA").build(privateKey);
            Certificate certificate = new JcaX509CertificateConverter().setProvider("BC").getCertificate(certBuilder.build(signer));

            keyStore.load(null, null);
            keyStore.setKeyEntry(KEYSTORE_ALIAS, privateKey, KEYSTORE_PASSWORD.toCharArray(), new Certificate[]{certificate});

            try (FileOutputStream fos = new FileOutputStream(keyStoreFile)) {
                keyStore.store(fos, KEYSTORE_PASSWORD.toCharArray());
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem switchDeviceItem = menu.findItem(R.id.action_switch_device);
        switchDeviceItem.setVisible(remoteControlLayout.getVisibility() == View.VISIBLE);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_switch_device) {
            disconnectAndReset(true);
            return true;
        }
        if (item.getItemId() == R.id.action_app_info) {
            View dialogView = getLayoutInflater().inflate(R.layout.dialog_app_info, null);
            TextView appInfoText = dialogView.findViewById(R.id.appInfoText);
            Linkify.addLinks(appInfoText, Linkify.WEB_URLS);
            appInfoText.setMovementMethod(LinkMovementMethod.getInstance());

            new AlertDialog.Builder(this)
                    .setView(dialogView)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void disconnectAndReset(boolean clearDevice) {
        executor.submit(() -> {
            if (tvCompanion != null) {
                tvCompanion.disconnect();
                tvCompanion = null;
            }
        });

        if (clearDevice) {
            isConnectingToSavedDevice = false;
            savedDeviceName = null;
            streamingDevice = null;
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply();
        }
        showDiscoveryUI();
    }

    private void setupRecyclerView() {
        RecyclerView devicesRecyclerView = findViewById(R.id.devicesRecyclerView);
        devicesRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        deviceAdapter = new DeviceAdapter(this);
        devicesRecyclerView.setAdapter(deviceAdapter);
    }

    private void startDiscovery() {
        if (nsdHelper != null) {
            nsdHelper.stopDiscovery();
        }
        deviceAdapter.setDevices(new ArrayList<>());

        if (isConnectingToSavedDevice && savedDeviceName != null) {
            discoveryStatusText.setText("Searching for " + savedDeviceName + "...");
        } else {
            discoveryStatusText.setText("Searching for devices...");
        }
        nsdHelper = new NsdHelper(this, this);
        nsdHelper.startDiscovery();
    }

    private void connectToDevice(DiscoveredDevice device) {
        tvCompanion = new TvCompanion(device.getHostAddress(), this);
        executor.submit(() -> {
            try {
                Log.d(TAG, "Attempting existing connection...");
                tvCompanion.connect();
                runOnUiThread(() -> {
                    Toast.makeText(this, "Connected to " + savedDeviceName, Toast.LENGTH_SHORT).show();
                    showRemoteUI();
                });
            } catch (Exception e) {
                Log.w(TAG, "Connection failed (" + e.getMessage() + "), starting pairing.", e);
                startPairing();
            }
        });
    }

    private void startPairing() {
        if (tvCompanion == null) return;
        tvCompanion.pair(new PairingClient.PairingCallback() {
            @Override
            public void onSecretRequested() {
                Log.d(TAG, "Secret requested");
                promptForPairingCode();
            }

            @Override
            public void onSuccess() {
                Log.d(TAG, "Pairing success");
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Pairing successful!", Toast.LENGTH_SHORT).show());
                // After successful pairing, try to connect again
                executor.submit(() -> {
                    try {
                        tvCompanion.connect();
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this, "Connected to " + savedDeviceName, Toast.LENGTH_SHORT).show();
                            showRemoteUI();
                        });
                    } catch (Exception e) {
                        Log.e(TAG, "Connection failed after pairing", e);
                        runOnUiThread(() -> onError(new Exception("Connection failed after pairing.")));
                    }
                });
            }

            @Override
            public void onError(Exception e) {
                Log.e(TAG, "Pairing error", e);
                // Avoid crashing on recursive onError calls from UI thread
                // runOnUiThread is not needed here if it's already on UI thread, checking...
                // But generally safe. The crash log indicates stack overflow from onError calling itself?
                // Or loop in disconnectAndReset?
                
                 runOnUiThread(() -> {
                     // Check if activity is still valid
                     if (!isFinishing() && !isDestroyed()) {
                        Toast.makeText(MainActivity.this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        disconnectAndReset(false);
                     }
                 });
            }
        });
    }

    private void promptForPairingCode() {
        runOnUiThread(() -> {
            final EditText input = new EditText(this);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);

            new AlertDialog.Builder(this)
                    .setTitle("Pairing Request")
                    .setMessage("Enter the code shown on your TV:")
                    .setView(input)
                    .setCancelable(false)
                    .setPositiveButton("Submit", (dialog, which) -> {
                        String code = input.getText().toString().trim();
                        executor.submit(() -> {
                            try {
                                tvCompanion.sendPairingSecret(code);
                            } catch (Exception e) {
                                Log.e(TAG, "Failed to send pairing secret", e);
                            }
                        });
                    })
                    .setNegativeButton("Cancel", (dialog, which) -> {
                        dialog.cancel();
                        disconnectAndReset(true);
                    })
                    .show();
        });
    }


    @Override
    public void onDeviceDiscovered(DiscoveredDevice device) {
        runOnUiThread(() -> {
            if (isConnectingToSavedDevice && device.getName().equals(savedDeviceName)) {
                isConnectingToSavedDevice = false;
                onDeviceClick(device);
            } else if (!isConnectingToSavedDevice) {
                discoveryStatusText.setText("Select a device:");
                deviceAdapter.addDevice(device);
            }
        });
    }

    @Override
    public void onDiscoveryFailed() {
        runOnUiThread(() -> {
            discoveryStatusText.setText("Discovery failed. Please try again.");
            isConnectingToSavedDevice = false;
        });
    }

    @Override
    public void onDeviceClick(DiscoveredDevice device) {
        if (nsdHelper != null) {
            nsdHelper.stopDiscovery();
        }

        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        editor.putString(KEY_DEVICE_NAME, device.getName());
        editor.apply();
        this.savedDeviceName = device.getName();
        this.streamingDevice = device;

        discoveryStatusText.setText("Connecting to " + device.getName() + "...");
        connectToDevice(device);
    }

    private void startStream(Surface surface) {
        if (rtpReceiver != null) {
            stopStream();
        }

        if (streamingDevice == null) {
            return;
        }

        new Thread(() -> {
            try {
                clientSocket = new Socket(streamingDevice.getHostAddress(), streamingDevice.getPort());
                rtpReceiver = new RTPReceiver(surface);
                rtpReceiver.start();
            } catch (Exception e) {
                Log.e(TAG, "Failed to connect to device", e);
            }
        }).start();
    }

    private void stopStream() {
        if (rtpReceiver != null) {
            new Thread(() -> {
                rtpReceiver.shutdown();
                rtpReceiver = null;
                Log.d(TAG, "RTP Receiver stopped");
                if (clientSocket != null && clientSocket.isConnected()) {
                    try {
                        clientSocket.close();
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to close socket", e);
                    }
                }
            }).start();
        }
    }

    private void showRemoteUI() {
        deviceDiscoveryLayout.setVisibility(View.GONE);
        remoteControlLayout.setVisibility(View.VISIBLE);
        Objects.requireNonNull(getSupportActionBar()).setTitle(savedDeviceName);
        invalidateOptionsMenu();
    }

    private void showDiscoveryUI() {
        deviceDiscoveryLayout.setVisibility(View.VISIBLE);
        remoteControlLayout.setVisibility(View.GONE);
        Objects.requireNonNull(getSupportActionBar()).setTitle(R.string.app_name);
        invalidateOptionsMenu();
        startDiscovery();
    }

    public void onError(Exception e) {
        Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        disconnectAndReset(false);
    }

    private void setupRemoteButtons() {
        findViewById(R.id.dpad_up).setOnClickListener(v -> executor.submit(() -> { if (tvCompanion != null) tvCompanion.dpadUp(); }));
        findViewById(R.id.dpad_down).setOnClickListener(v -> executor.submit(() -> { if (tvCompanion != null) tvCompanion.dpadDown(); }));
        findViewById(R.id.dpad_left).setOnClickListener(v -> executor.submit(() -> { if (tvCompanion != null) tvCompanion.dpadLeft(); }));
        findViewById(R.id.dpad_right).setOnClickListener(v -> executor.submit(() -> { if (tvCompanion != null) tvCompanion.dpadRight(); }));
        findViewById(R.id.dpad_center).setOnClickListener(v -> executor.submit(() -> { if (tvCompanion != null) tvCompanion.dpadCenter(); }));
        findViewById(R.id.home_button).setOnClickListener(v -> executor.submit(() -> { if (tvCompanion != null) tvCompanion.home(); }));
        findViewById(R.id.back_button).setOnClickListener(v -> executor.submit(() -> { if (tvCompanion != null) tvCompanion.back(); }));

        findViewById(R.id.volume_up).setOnClickListener(v -> executor.submit(() -> { if (tvCompanion != null) tvCompanion.volumeUp(); }));
        findViewById(R.id.volume_down).setOnClickListener(v -> executor.submit(() -> { if (tvCompanion != null) tvCompanion.volumeDown(); }));

        findViewById(R.id.launch_url_button).setOnClickListener(v -> showUrlSelectionDialog());
    }

    private void showUrlSelectionDialog() {
        List<SavedUrl> savedUrls = getSavedUrls();
        List<String> items = new ArrayList<>();
        for (SavedUrl savedUrl : savedUrls) {
            items.add(savedUrl.getTitle());
        }
        items.add("Add new URL...");

        new AlertDialog.Builder(this)
                .setTitle("Select URL to launch")
                .setItems(items.toArray(new CharSequence[0]), (dialog, which) -> {
                    if (which == items.size() - 1) {
                        // "Add new URL..." clicked
                        showAddUrlDialog();
                    } else {
                        // A saved URL is clicked
                        String url = savedUrls.get(which).getUrl();
                        if (!url.isEmpty()) {
                            executor.submit(() -> {
                                if (tvCompanion != null) tvCompanion.launchUrl(url);
                            });
                        }
                    }
                })
                .show();
    }

    private void showAddUrlDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        // Setting padding programmatically. A better approach would be to create a new layout xml file.
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);

        final EditText titleInput = new EditText(this);
        titleInput.setHint("Title (e.g., YouTube)");

        final EditText urlInput = new EditText(this);
        urlInput.setHint("URL (e.g., https://www.youtube.com)");
        urlInput.setInputType(InputType.TYPE_TEXT_VARIATION_URI);

        layout.addView(titleInput);
        layout.addView(urlInput);

        new AlertDialog.Builder(this)
                .setTitle("Add New URL")
                .setView(layout)
                .setPositiveButton("Save", (dialog, which) -> {
                    String title = titleInput.getText().toString().trim();
                    String url = urlInput.getText().toString().trim();
                    if (!title.isEmpty() && !url.isEmpty()) {
                        List<SavedUrl> savedUrls = getSavedUrls();
                        savedUrls.add(new SavedUrl(title, url));
                        saveUrls(savedUrls);
                        Toast.makeText(this, "URL saved!", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", (dialog, which) -> dialog.cancel())
                .show();
    }

    private List<SavedUrl> getSavedUrls() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_SAVED_URLS, "[]");
        List<SavedUrl> urls = new ArrayList<>();
        try {
            JSONArray jsonArray = new JSONArray(json);
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject jsonObject = jsonArray.getJSONObject(i);
                urls.add(new SavedUrl(jsonObject.getString("title"), jsonObject.getString("url")));
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing saved URLs", e);
        }
        return urls;
    }

    private void saveUrls(List<SavedUrl> urls) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        JSONArray jsonArray = new JSONArray();
        for (SavedUrl savedUrl : urls) {
            try {
                JSONObject jsonObject = new JSONObject();
                jsonObject.put("title", savedUrl.getTitle());
                jsonObject.put("url", savedUrl.getUrl());
                jsonArray.put(jsonObject);
            } catch (JSONException e) {
                Log.e(TAG, "Error creating JSON for URL", e);
            }
        }
        prefs.edit().putString(KEY_SAVED_URLS, jsonArray.toString()).apply();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopStream();
        executor.submit(() -> {
            if (tvCompanion != null) {
                tvCompanion.disconnect();
            }
        });
        executor.shutdown();
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        Log.d(TAG, "Surface created.");
        if (streamingDevice != null) {
            startStream(holder.getSurface());
        }
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        // Not used
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        Log.d(TAG, "Surface destroyed.");
        stopStream();
    }
}