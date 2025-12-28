package com.android.systemui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.ext.power.BatteryChargeLimit;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import vendor.benzeneos.battery.IBattery;

public class BootReceiver extends BroadcastReceiver {
    static final String TAG = "BootReceiver";

    private static final String BATTERY_SERVICE_NAME = "vendor.benzeneos.battery.IBattery/default";
    private static final int MAX_RETRIES = 5;
    private static final long RETRY_DELAY_MS = 1000;

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "onReceive: " + intent.getAction() + ", user=" + context.getUser());

        if (!context.getUser().isSystem()) {
            Log.d(TAG, "Not system user, skipping");
            return;
        }

        boolean enabled = BatteryChargeLimit.isChargeLimitEnabled(context);
        Log.d(TAG, "Charge limit enabled: " + enabled);

        if (enabled) {
            // Use goAsync() to extend the broadcast timeout and run on background thread
            final PendingResult pendingResult = goAsync();
            new Thread(() -> {
                try {
                    applyChargeLimit(context);
                } finally {
                    pendingResult.finish();
                }
            }, "BootReceiver-ChargeLimit").start();
        }
    }

    private static void applyChargeLimit(Context context) {
        // HAL may not be ready immediately at boot, retry a few times
        IBinder binder = null;
        for (int i = 0; i < MAX_RETRIES; i++) {
            binder = ServiceManager.getService(BATTERY_SERVICE_NAME);
            if (binder != null) break;
            Log.d(TAG, BATTERY_SERVICE_NAME + " not available, retrying... (" + (i + 1) + "/" + MAX_RETRIES + ")");
            try {
                Thread.sleep(RETRY_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        if (binder == null) {
            Log.e(TAG, BATTERY_SERVICE_NAME + " is not available after retries");
            return;
        }
        var service = IBattery.Stub.asInterface(binder);

        int stopLevel = BatteryChargeLimit.getChargeStopLevel(context);
        int startLevel = BatteryChargeLimit.getChargeStartLevel(context);

        try {
            // Apply the saved charge levels
            service.setChargeLimit(stopLevel, startLevel);

            // Use CUSTOM policy if non-default levels, otherwise LONGLIFE
            int policy = (stopLevel == BatteryChargeLimit.DEFAULT_CHARGE_LEVEL)
                    ? IBattery.ChargingPolicy.LONGLIFE
                    : IBattery.ChargingPolicy.CUSTOM;
            service.setChargingPolicy(policy);

            Log.d(TAG, "Applied charge limit: stop=" + stopLevel + ", start=" + startLevel
                    + ", policy=" + policy);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to apply charge limit", e);
        }
    }
}
