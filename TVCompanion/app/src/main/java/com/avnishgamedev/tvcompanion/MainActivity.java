package com.avnishgamedev.tvcompanion;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int REQUEST_OVERLAY_PERMISSION = 1234;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Overlay Permission needed for App to receive ACTION_BOOT_COMPLETED broadcast (since Android 10)
        checkAndRequestOverlayPermission();

        if (!isCompanionServiceRunning()) {
            startCompanionService();
            finish();
        }
    }

    private void startCompanionService() {
        Intent serviceIntent = new Intent(this, CompanionService.class);
        startForegroundService(serviceIntent);
    }

    private boolean isCompanionServiceRunning() {
        ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo serviceInfo : activityManager.getRunningServices(Integer.MAX_VALUE)) {
            if (CompanionService.class.getName().equals(serviceInfo.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    private void checkAndRequestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                // Try to request permission
                requestOverlayPermission();
            } else {
                Log.d(TAG, "Overlay permission already granted");
            }
        } else {
            // Permission automatically granted on Android 5.1 and below
            Log.d(TAG, "Overlay permission not needed on this Android version");
        }
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));

                // Check if the settings activity exists before launching
                if (intent.resolveActivity(getPackageManager()) != null) {
                    startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION);
                } else {
                    // Device doesn't support overlay permission settings (e.g., Android TV)
                    Log.w(TAG, "Overlay permission settings not available on this device");
                    Toast.makeText(this,
                            "Overlay not supported on this device - continuing without it",
                            Toast.LENGTH_LONG).show();
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to request overlay permission", e);
                Toast.makeText(this,
                        "Overlay permission unavailable - continuing without it",
                        Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    Log.d(TAG, "Overlay permission granted");
                    Toast.makeText(this, "Overlay permission granted", Toast.LENGTH_SHORT).show();
                } else {
                    Log.w(TAG, "Overlay permission denied");
                    Toast.makeText(this, "Overlay permission denied - overlay will not work",
                            Toast.LENGTH_SHORT).show();
                }
            }
        }
    }
}