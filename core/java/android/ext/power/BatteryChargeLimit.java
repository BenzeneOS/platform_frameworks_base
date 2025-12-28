package android.ext.power;

import android.content.Context;
import android.ext.settings.BoolSetting;
import android.ext.settings.IntSetting;
import android.ext.settings.Setting;
import android.os.Build;
import android.os.IBinder;
import android.os.IInterface;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.Log;

/** @hide */
public class BatteryChargeLimit {
    private static final String TAG = "BatteryChargeLimit";

    /** Default charge level for LONGLIFE policy (Google's default). */
    public static final int DEFAULT_CHARGE_LEVEL = 80;

    /** Minimum allowed charge level (stop). Must be >= MIN_START_LEVEL + MIN_LEVEL_GAP. */
    public static final int MIN_CHARGE_LEVEL = 50;

    /** Maximum allowed charge level. */
    public static final int MAX_CHARGE_LEVEL = 100;

    /** Minimum allowed start level (kernel constraint). */
    public static final int MIN_START_LEVEL = 45;

    /** Default gap between stop and start levels. */
    public static final int DEFAULT_LEVEL_GAP = 10;

    /** Minimum gap between stop and start levels. */
    public static final int MIN_LEVEL_GAP = 5;

    private static final BoolSetting SETTING = new BoolSetting(Setting.Scope.GLOBAL,
            Settings.Global.BATTERY_CHARGE_LIMIT, false);

    private static final IntSetting STOP_LEVEL_SETTING = new IntSetting(Setting.Scope.GLOBAL,
            Settings.Global.BATTERY_CHARGE_STOP_LEVEL, DEFAULT_CHARGE_LEVEL);

    private static final IntSetting START_LEVEL_SETTING = new IntSetting(Setting.Scope.GLOBAL,
            Settings.Global.BATTERY_CHARGE_START_LEVEL, DEFAULT_CHARGE_LEVEL - DEFAULT_LEVEL_GAP);

    private static final String BATTERY_SERVICE_NAME = "vendor.benzeneos.battery.IBattery/default";

    public static BoolSetting getSetting() {
        return SETTING;
    }

    public static IntSetting getStopLevelSetting() {
        return STOP_LEVEL_SETTING;
    }

    public static IntSetting getStartLevelSetting() {
        return START_LEVEL_SETTING;
    }

    /**
     * Get the current charge stop level.
     *
     * @param context The context
     * @return The charge stop level (50-100)
     */
    public static int getChargeStopLevel(Context context) {
        int level = STOP_LEVEL_SETTING.get(context);
        return Math.max(MIN_CHARGE_LEVEL, Math.min(MAX_CHARGE_LEVEL, level));
    }

    /**
     * Get the current charge start level.
     *
     * @param context The context
     * @return The charge start level
     */
    public static int getChargeStartLevel(Context context) {
        int stopLevel = getChargeStopLevel(context);
        int level = START_LEVEL_SETTING.get(context);
        // Ensure start is at least MIN_LEVEL_GAP below stop and >= MIN_START_LEVEL
        int startLevel = Math.min(level, stopLevel - MIN_LEVEL_GAP);
        return Math.max(startLevel, MIN_START_LEVEL);
    }

    /**
     * Set custom charge levels.
     *
     * @param context The context
     * @param stopLevel The level to stop charging at (50-100)
     * @param startLevel The level to resume charging at (must be < stopLevel - MIN_LEVEL_GAP)
     * @return true if successful
     */
    public static boolean setChargeLevels(Context context, int stopLevel, int startLevel) {
        if (!isGoogleDevice()) {
            return false;
        }

        // Validate inputs
        if (stopLevel < MIN_CHARGE_LEVEL || stopLevel > MAX_CHARGE_LEVEL) {
            Log.w(TAG, "Invalid stop level: " + stopLevel);
            return false;
        }
        if (startLevel < MIN_START_LEVEL) {
            Log.w(TAG, "Start level below minimum: " + startLevel);
            return false;
        }
        if (startLevel >= stopLevel - MIN_LEVEL_GAP) {
            Log.w(TAG, "Start level too close to stop level");
            return false;
        }

        // Save to settings
        STOP_LEVEL_SETTING.put(context, stopLevel);
        START_LEVEL_SETTING.put(context, startLevel);

        // Apply to HAL if charge limit is enabled
        if (isChargeLimitEnabled(context)) {
            return applyChargeLevels(stopLevel, startLevel);
        }

        return true;
    }

    /**
     * Apply charge levels to the HAL.
     * Uses reflection to avoid class loading issues if the HAL isn't available.
     */
    private static boolean applyChargeLevels(int stopLevel, int startLevel) {
        try {
            IInterface battery = getBattery();
            if (battery != null) {
                // Use reflection to call setChargeLimit to avoid direct class dependency
                battery.getClass().getMethod("setChargeLimit", int.class, int.class)
                        .invoke(battery, stopLevel, startLevel);
                Log.i(TAG, "Applied charge levels: stop=" + stopLevel + ", start=" + startLevel);
                return true;
            }
        } catch (ReflectiveOperationException e) {
            Log.e(TAG, "Failed to apply charge levels", e);
        }
        return false;
    }

    /**
     * Get the IBattery service using reflection to avoid class loading issues.
     * Gets a fresh reference each time to handle service restarts.
     */
    private static IInterface getBattery() {
        IBinder binder = ServiceManager.getService(BATTERY_SERVICE_NAME);
        if (binder == null) {
            return null;
        }
        try {
            // Use reflection to get the Stub class and call asInterface
            Class<?> stubClass = Class.forName("vendor.benzeneos.battery.IBattery$Stub");
            return (IInterface) stubClass.getMethod("asInterface", IBinder.class)
                    .invoke(null, binder);
        } catch (ReflectiveOperationException e) {
            Log.e(TAG, "Failed to get IBattery interface", e);
            return null;
        }
    }

    public static boolean isChargeLimitEnabled(Context context) {
        if (!isGoogleDevice()) {
            return false;
        }
        return SETTING.get(context);
    }

    public static boolean isGoogleDevice() {
        return "Google".equals(Build.MANUFACTURER);
    }
}
