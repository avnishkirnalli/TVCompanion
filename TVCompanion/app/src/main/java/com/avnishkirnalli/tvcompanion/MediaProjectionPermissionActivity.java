package com.avnishkirnalli.tvcompanion;

import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * This activity is merely used to get the permission from MediaProjection API to capture the screen.
 */
public class MediaProjectionPermissionActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Make the activity transparent
        setTheme(android.R.style.Theme_Translucent_NoTitleBar);

        ResultReceiver receiver = getIntent().getParcelableExtra("resultReceiver");
        if (receiver == null) {
            Log.e("MediaProjectionPermissionActivity", "No ResultReceiver provided. Finishing.");
            finish();
            return;
        }

        ActivityResultLauncher<Intent> permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    Bundle bundle = new Bundle();
                    if (result.getData() != null) {
                        bundle.putParcelable("data", result.getData());
                    }
                    receiver.send(result.getResultCode(), bundle);
                    finish(); // Finish the activity after the result is sent
                }
        );

        MediaProjectionManager mediaProjectionManager = getSystemService(MediaProjectionManager.class);
        if (mediaProjectionManager != null) {
            permissionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent());
        } else {
            Log.e("MediaProjectionPermissionActivity", "MediaProjectionManager is not available. Finishing.");
            receiver.send(RESULT_CANCELED, null);
            finish();
        }
    }
}
