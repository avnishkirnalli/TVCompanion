package com.avnishgamedev.tvcompanion;

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

        ResultReceiver receiver = getIntent().getParcelableExtra("resultReceiver");

        ActivityResultLauncher<Intent> a = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (receiver != null) {
                        Bundle bundle = new Bundle();
                        bundle.putParcelable("data", result.getData());
                        receiver.send(result.getResultCode(), bundle);
                    }
                }
        );

        a.launch(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent());
    }
}
