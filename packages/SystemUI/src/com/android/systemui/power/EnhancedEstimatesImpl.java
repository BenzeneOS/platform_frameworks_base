package com.android.systemui.power;

import android.content.Context;
import android.os.BatteryStatsManager;
import android.os.BatteryUsageStats;
import android.util.Log;

import com.android.settingslib.fuelgauge.Estimate;
import com.android.settingslib.fuelgauge.EstimateKt;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Application;

import javax.inject.Inject;

@SysUISingleton
public class EnhancedEstimatesImpl implements EnhancedEstimates {
    private static final String TAG = "EnhancedEstimatesImpl";

    private final BatteryStatsManager mBatteryStatsManager;

    @Inject
    public EnhancedEstimatesImpl(@Application Context context) {
        mBatteryStatsManager = context.getSystemService(BatteryStatsManager.class);
    }

    @Override
    public boolean isHybridNotificationEnabled() {
        return false;
    }

    @Override
    public Estimate getEstimate() {
        if (mBatteryStatsManager == null) {
            return getUnknownEstimate();
        }

        BatteryUsageStats batteryUsageStats = null;
        try {
            batteryUsageStats = mBatteryStatsManager.getBatteryUsageStats();
            return new Estimate(
                    batteryUsageStats.getBatteryTimeRemainingMs(),
                    false /* isBasedOnUsage */,
                    EstimateKt.AVERAGE_TIME_TO_DISCHARGE_UNKNOWN);
        } catch (RuntimeException e) {
            Log.e(TAG, "getEstimate() from getBatteryUsageStats()", e);
            return getUnknownEstimate();
        } finally {
            if (batteryUsageStats != null) {
                try {
                    batteryUsageStats.close();
                } catch (Exception e) {
                    Log.e(TAG, "BatteryUsageStats.close() failed", e);
                }
            }
        }
    }

    @Override
    public long getLowWarningThreshold() {
        return 0;
    }

    @Override
    public long getSevereWarningThreshold() {
        return 0;
    }

    @Override
    public boolean getLowWarningEnabled() {
        return true;
    }

    private static Estimate getUnknownEstimate() {
        return new Estimate(
                EstimateKt.ESTIMATE_MILLIS_UNKNOWN,
                false /* isBasedOnUsage */,
                EstimateKt.AVERAGE_TIME_TO_DISCHARGE_UNKNOWN);
    }
}
