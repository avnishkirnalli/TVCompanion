package com.avnishgamedev.tvcompanion;

import static com.avnishgamedev.tvcompanion.CompanionService.ACTION_MEDIA_PROJECTION_PERMISSIONS_RESULT;

import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

public class MediaProjectionSupportActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ActivityResultLauncher<Intent> a = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    Log.d("MediaProjection", "Result Code: " + result.getResultCode());
                    Intent i = new Intent(ACTION_MEDIA_PROJECTION_PERMISSIONS_RESULT);
                    i.putExtra("resultCode", result.getResultCode());
                    i.putExtra("data", result.getData());
                    i.setPackage(getPackageName());
                    sendBroadcast(i);
                    finish();
                }
        );

        a.launch(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent());
    }
}
