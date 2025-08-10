package com.avnishgamedev.tvcompanion;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Overlay Permission needed for App to receive ACTION_BOOT_COMPLETED broadcast (since Android 10)
        requestOverlayPermission();

        if (!isCompanionServiceRunning()) {
            startCompanionService();
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

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())
            );
            ActivityResultLauncher<Intent> launcher = registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() != RESULT_OK) {
                            Toast.makeText(this, "Overlay Permission is needed to use app!", Toast.LENGTH_SHORT).show();
                            requestOverlayPermission();
                        }
                    }
            );
            launcher.launch(intent);
        }
    }
}