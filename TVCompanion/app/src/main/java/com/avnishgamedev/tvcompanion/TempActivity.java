package com.avnishgamedev.tvcompanion;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

public class TempActivity extends AppCompatActivity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.d("TempActivity", "Starting Service");
        Intent serviceIntent = new Intent(this, CompanionService.class);
        startForegroundService(serviceIntent);
    }
}
