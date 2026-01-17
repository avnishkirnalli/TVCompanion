package com.avnishkirnalli.tvcompanion;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * This receiver is used to start the CompanionService when the device boots.
 */
public class OnDeviceBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            //Intent serviceIntent = new Intent(context, CompanionService.class);
            //context.startForegroundService(serviceIntent);
        }
    }
}
