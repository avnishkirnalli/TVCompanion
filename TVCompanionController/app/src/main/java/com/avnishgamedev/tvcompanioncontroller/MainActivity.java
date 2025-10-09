package com.avnishgamedev.tvcompanioncontroller;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;

public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback {
    private static final String TAG = "MainActivity";
    private static final String SERVICE_TYPE = "_http._tcp.";

    // Video dimensions
    private static final int VIDEO_WIDTH = 1280;
    private static final int VIDEO_HEIGHT = 720;

    NsdManager nsdManager;
    NsdManager.DiscoveryListener discoveryListener;
    NsdManager.ResolveListener resolveListener;
    NsdServiceInfo connectedService;
    Socket socket;
    DataInputStream in;
    DataOutputStream out;

    // Widgets
    FrameLayout loadingLayout;
    Button btnVolumeUp;
    Button btnVolumeDown;
    Button btnStartStream;
    Button btnStopStream;
    EditText etVolume;
    Button btnSetVolume;
    Button btnAddUrl;
    Button btnLaunchUrl;
    AutoCompleteTextView etUrl;
    SurfaceView surfaceView;
    ProgressBar progressBar;

    // URL List
    ArrayList<String> urlList;
    ArrayAdapter<String> urlAdapter;
    SharedPreferences prefs;
    private static final String KEY_URLS = "saved_urls";
    private static final String PREFS_NAME = "TVCompanionPrefs";

    // RTP Streaming
    private RTPReceiver rtpReceiver;
    private boolean isSurfaceReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        nsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);

        initializeDiscoveryListener();
        initializeResolveListener();

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);

        // Initialize Widgets
        loadingLayout = findViewById(R.id.loadingLayout);
        surfaceView = findViewById(R.id.surfaceView);
        progressBar = findViewById(R.id.progressBar);

        // Setup SurfaceView
        surfaceView.getHolder().addCallback(this);

        btnVolumeUp = findViewById(R.id.btnVolumeUp);
        btnVolumeUp.setOnClickListener(v -> sendCommand("volume_up"));

        btnVolumeDown = findViewById(R.id.btnVolumeDown);
        btnVolumeDown.setOnClickListener(v -> sendCommand("volume_down"));

        etVolume = findViewById(R.id.etVolume);

        btnSetVolume = findViewById(R.id.btnSetVolume);
        btnSetVolume.setOnClickListener(v -> sendCommand("volume_set " + etVolume.getText().toString()));

        etUrl = findViewById(R.id.etUrl);

        btnAddUrl = findViewById(R.id.btnAddUrl);

        btnLaunchUrl = findViewById(R.id.btnLaunchUrl);

        btnStartStream = findViewById(R.id.btnStartStream);
        btnStartStream.setOnClickListener(v -> {
            sendCommand("start_stream");
            startVideoStream();
        });

        btnStopStream = findViewById(R.id.btnStopStream);
        btnStopStream.setOnClickListener(v -> {
            sendCommand("stop_stream");
            stopVideoStream();
        });

        // Initialize SharedPreferences
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // Load saved URLs
        urlList = getUrlList();
        if (urlList == null) {
            urlList = new ArrayList<>();
        }

        // Setup adapter for dropdown
        urlAdapter = new ArrayAdapter<>(this,
                R.layout.dropdown_item, urlList);
        etUrl.setAdapter(urlAdapter);

        // Add URL button click
        btnAddUrl.setOnClickListener(v -> {
                String newUrl = etUrl.getText().toString().trim();
                if (!newUrl.isEmpty()) {
                    if (!urlList.contains(newUrl)) {
                        urlList.add(newUrl);
                        urlAdapter.notifyDataSetChanged();
                        saveUrlList(urlList);
                        Toast.makeText(MainActivity.this,
                                "URL saved", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(MainActivity.this,
                                "URL already exists", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Toast.makeText(MainActivity.this,
                            "Please enter a URL", Toast.LENGTH_SHORT).show();
                }
        });

        btnLaunchUrl.setOnClickListener(v -> {
                String selectedUrl = etUrl.getText().toString().trim();
                if (!selectedUrl.isEmpty()) {
                    Toast.makeText(MainActivity.this,
                            "Launching: " + selectedUrl, Toast.LENGTH_SHORT).show();
                    sendCommand("launch_url " + selectedUrl);
                } else {
                    Toast.makeText(MainActivity.this,
                            "Please select or enter a URL", Toast.LENGTH_SHORT).show();
                }
        });

        // Show dropdown when clicked
        etUrl.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus && !urlList.isEmpty()) {
                etUrl.showDropDown();
            }
        });
    }

    private void saveUrlList(ArrayList<String> list) {
        SharedPreferences.Editor editor = prefs.edit();
        Gson gson = new Gson();
        String json = gson.toJson(list);
        editor.putString(KEY_URLS, json);
        editor.apply();
    }

    private ArrayList<String> getUrlList() {
        Gson gson = new Gson();
        String json = prefs.getString(KEY_URLS, null);
        Type type = new TypeToken<ArrayList<String>>() {}.getType();
        return gson.fromJson(json, type);
    }

    // SurfaceHolder.Callback methods
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.d(TAG, "Surface created");
        isSurfaceReady = true;
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.d(TAG, "Surface changed: " + width + "x" + height);
        // Adjust SurfaceView to maintain aspect ratio
        adjustAspectRatio(width, height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.d(TAG, "Surface destroyed");
        isSurfaceReady = false;
        stopVideoStream();
    }

    private void adjustAspectRatio(int viewWidth, int viewHeight) {
        if (viewWidth == 0 || viewHeight == 0) return;

        float videoAspectRatio = (float) VIDEO_WIDTH / VIDEO_HEIGHT;
        float viewAspectRatio = (float) viewWidth / viewHeight;

        int newWidth, newHeight;

        if (viewAspectRatio > videoAspectRatio) {
            // View is wider than video - fit height
            newHeight = viewHeight;
            newWidth = (int) (viewHeight * videoAspectRatio);
        } else {
            // View is taller than video - fit width
            newWidth = viewWidth;
            newHeight = (int) (viewWidth / videoAspectRatio);
        }

        // Update SurfaceView layout params to maintain aspect ratio
        RelativeLayout.LayoutParams layoutParams =
                (RelativeLayout.LayoutParams) surfaceView.getLayoutParams();
        layoutParams.width = newWidth;
        layoutParams.height = newHeight;
        layoutParams.addRule(RelativeLayout.CENTER_IN_PARENT);

        surfaceView.post(() -> surfaceView.setLayoutParams(layoutParams));

        Log.d(TAG, "Adjusted aspect ratio: " + newWidth + "x" + newHeight);
    }

    private void startVideoStream() {
        if (rtpReceiver != null) {
            toastOnUiThread("Stream already running");
            return;
        }

        if (!isSurfaceReady) {
            toastOnUiThread("Display not ready");
            return;
        }

        try {
            runOnUiThread(() -> progressBar.setVisibility(View.VISIBLE));

            rtpReceiver = new RTPReceiver(surfaceView.getHolder().getSurface());
            rtpReceiver.start();

            Log.d(TAG, "RTP receiver started");
            toastOnUiThread("Stream started");

            // Hide progress bar after 2 seconds
            surfaceView.postDelayed(() -> {
                runOnUiThread(() -> progressBar.setVisibility(View.GONE));
            }, 2000);

        } catch (IOException e) {
            Log.e(TAG, "Failed to start RTP receiver", e);
            toastOnUiThread("Failed to start stream: " + e.getMessage());
            runOnUiThread(() -> progressBar.setVisibility(View.GONE));
        }
    }

    private void stopVideoStream() {
        if (rtpReceiver != null) {
            rtpReceiver.shutdown();
            rtpReceiver = null;
            Log.d(TAG, "RTP receiver stopped");
            toastOnUiThread("Stream stopped");
        }
        runOnUiThread(() -> progressBar.setVisibility(View.GONE));
    }

    private void initializeDiscoveryListener() {
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String regType) {
                Log.d(TAG, "Service discovery started");
            }

            @Override
            public void onServiceFound(NsdServiceInfo service) {
                Log.d(TAG, "Service discovery success: " + service);
                if (!service.getServiceType().equals(SERVICE_TYPE)) {
                    Log.d(TAG, "Unknown Service Type: " + service.getServiceType());
                } else if (service.getServiceName().contains("TVCompanionService")) {
                    nsdManager.resolveService(service, resolveListener);
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo service) {
                Log.e(TAG, "Service lost: " + service);
                if (connectedService != null) {
                    if (connectedService.getServiceName().equals(service.getServiceName())) {
                        connectedService = null;
                        setLoading(true);

                        stopVideoStream();

                        if (socket != null) {
                            try {
                                socket.close();
                                if (in != null) in.close();
                                if (out != null) out.close();
                            } catch (IOException e) {
                                Log.e(TAG, "Error closing socket", e);
                            }
                        }
                    }
                }
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                Log.i(TAG, "Discovery stopped: " + serviceType);
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Discovery failed: Error code:" + errorCode);
                nsdManager.stopServiceDiscovery(this);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Discovery failed: Error code:" + errorCode);
                nsdManager.stopServiceDiscovery(this);
            }
        };
    }

    private void initializeResolveListener() {
        resolveListener = new NsdManager.ResolveListener() {

            @Override
            public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                Log.e(TAG, "Resolve failed: " + errorCode);
            }

            @Override
            public void onServiceResolved(NsdServiceInfo serviceInfo) {
                Log.d(TAG, "Resolve Succeeded: " + serviceInfo);
                connectedService = serviceInfo;
                connectToServer();
            }
        };
    }

    private void connectToServer() {
        new Thread(() -> {
            try {
                InetAddress host = connectedService.getHost();
                int port = connectedService.getPort();
                Log.d(TAG, "Attempting to connect to host: " + host + ", port: " + port);

                socket = new Socket(host, port);
                in = new DataInputStream(socket.getInputStream());
                out = new DataOutputStream(socket.getOutputStream());

                Log.d(TAG, "Connected to server");
                setLoading(false);
                toastOnUiThread("Connected to TV Companion");

            } catch (IOException e) {
                Log.e(TAG, "Connection failed", e);
                toastOnUiThread("Connection failed: " + e.getMessage());
            }
        }).start();
    }

    private void sendCommand(String command) {
        if (socket != null && socket.isConnected()) {
            new Thread(() -> {
                try {
                    out.writeUTF(command);
                    out.flush();
                    String response = in.readUTF();

                    if (response.equals("ack")) {
                        toastOnUiThread("Acknowledged");
                    } else if (response.equals("noack")) {
                        toastOnUiThread("Not Acknowledged");
                    } else {
                        toastOnUiThread("Unknown response: " + response);
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Command failed", e);
                    toastOnUiThread("Command failed: " + e.getMessage());
                }
            }).start();
        } else {
            toastOnUiThread("Not connected to server");
        }
    }

    private void toastOnUiThread(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    private void setLoading(boolean state) {
        runOnUiThread(() -> loadingLayout.setVisibility(state ? View.VISIBLE : View.GONE));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (discoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Discovery listener already stopped", e);
            }
        }

        stopVideoStream();

        if (socket != null) {
            try {
                socket.close();
                if (in != null) in.close();
                if (out != null) out.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing socket", e);
            }
        }
    }
}
