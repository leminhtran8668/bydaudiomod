package com.bydaudiomod;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "AudioModBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.i(TAG, "Boot/ACC received: " + action);

        // Door sound service (if enabled)
        SharedPreferences doorPrefs = context.getSharedPreferences(
                DoorSoundService.PREF_NAME, Context.MODE_PRIVATE);
        if (doorPrefs.getBoolean(DoorSoundService.KEY_ENABLED, false)) {
            try {
                Intent svc = new Intent(context, DoorSoundService.class);
                context.startForegroundService(svc);
                Log.i(TAG, "DoorSoundService started");
            } catch (Exception e) {
                Log.e(TAG, "Failed to start DoorSoundService", e);
            }
        }

        // Feedback service (always start - toggles controlled by prefs)
        try {
            Intent fb = new Intent(context, BydAutoService.class);
            context.startForegroundService(fb);
            Log.i(TAG, "BydAutoService started");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start BydAutoService", e);
        }
    }
}
