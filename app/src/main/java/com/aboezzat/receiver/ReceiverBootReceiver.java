package com.aboezzat.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/** Restarts the local receiver service after reboot or app replacement. */
public final class ReceiverBootReceiver extends BroadcastReceiver {
    private static final String TAG = "ReceiverBootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        try {
            Intent service = new Intent(context, ReceiverServerService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
        } catch (Exception e) {
            // Some TV firmware restricts background starts; opening Receiver will retry.
            Log.w(TAG, "Automatic receiver start was restricted by the system", e);
        }
    }
}
