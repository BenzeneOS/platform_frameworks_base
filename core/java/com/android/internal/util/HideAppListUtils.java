/*
 * Copyright (C) 2025 Amaan Qureshi <contact@amaanq.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.util;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.SparseArray;

import java.util.Collections;
import java.util.Set;

/**
 * Utility class for hiding apps from other apps' PackageManager queries.
 * Uses caching with ContentObserver to avoid reading Settings on every call.
 * @hide
 */
public final class HideAppListUtils {
    private static final Object sLock = new Object();

    // Per-user caches
    private static final SparseArray<Set<String>> sHiddenAppsCache = new SparseArray<>();
    private static final SparseArray<Set<String>> sWhitelistedAppsCache = new SparseArray<>();

    private static volatile boolean sInitialized = false;

    private HideAppListUtils() {}

    private static boolean isBootCompleted() {
        return SystemProperties.getBoolean("sys.boot_completed", false);
    }

    /**
     * Initialize the cache and register ContentObservers.
     * Must be called from system server during boot.
     * @hide
     */
    public static void init(Context context) {
        synchronized (sLock) {
            if (sInitialized) return;
            Handler handler = new Handler(Looper.getMainLooper());

            context.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.HIDE_APPLIST),
                true, // notifyForDescendants - catches per-user changes
                new ContentObserver(handler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        synchronized (sLock) {
                            sHiddenAppsCache.clear();
                        }
                    }
                },
                UserHandle.USER_ALL
            );

            context.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.HIDE_APPLIST_WHITELIST),
                true,
                new ContentObserver(handler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        synchronized (sLock) {
                            sWhitelistedAppsCache.clear();
                        }
                    }
                },
                UserHandle.USER_ALL
            );

            sInitialized = true;
        }
    }

    /**
     * Check if a package should be hidden from other apps.
     *
     * @param context The context to use for accessing settings
     * @param packageName The package name to check
     * @param userId The user ID to check for
     * @return true if the package should be hidden, false otherwise
     */
    public static boolean shouldHideAppList(Context context, String packageName, int userId) {
        if (!isBootCompleted() || context == null || packageName == null) {
            return false;
        }
        Set<String> apps = getHiddenApps(context, userId);
        return !apps.isEmpty() && apps.contains(packageName);
    }

    /**
     * Check if a package should be hidden from other apps.
     * Uses the current user.
     *
     * @param context The context to use for accessing settings
     * @param packageName The package name to check
     * @return true if the package should be hidden, false otherwise
     */
    public static boolean shouldHideAppList(Context context, String packageName) {
        return shouldHideAppList(context, packageName, UserHandle.myUserId());
    }

    /**
     * Check if a calling package is whitelisted to see all apps.
     *
     * @param context The context to use for accessing settings
     * @param callingPackage The package name of the caller to check
     * @param userId The user ID to check for
     * @return true if the caller is whitelisted, false otherwise
     */
    public static boolean isCallerWhitelisted(Context context, String callingPackage, int userId) {
        if (!isBootCompleted() || context == null || callingPackage == null) {
            return false;
        }
        Set<String> whitelist = getWhitelistedApps(context, userId);
        return !whitelist.isEmpty() && whitelist.contains(callingPackage);
    }

    /**
     * Check if a calling package is whitelisted to see all apps.
     * Uses the current user.
     *
     * @param context The context to use for accessing settings
     * @param callingPackage The package name of the caller to check
     * @return true if the caller is whitelisted, false otherwise
     */
    public static boolean isCallerWhitelisted(Context context, String callingPackage) {
        return isCallerWhitelisted(context, callingPackage, UserHandle.myUserId());
    }

    private static Set<String> getHiddenApps(Context context, int userId) {
        synchronized (sLock) {
            Set<String> cached = sHiddenAppsCache.get(userId);
            if (cached != null) {
                return cached;
            }

            String apps = Settings.Secure.getStringForUser(
                context.getContentResolver(),
                Settings.Secure.HIDE_APPLIST,
                userId
            );
            Set<String> parsed = parseApps(apps);
            sHiddenAppsCache.put(userId, parsed);
            return parsed;
        }
    }

    private static Set<String> getWhitelistedApps(Context context, int userId) {
        synchronized (sLock) {
            Set<String> cached = sWhitelistedAppsCache.get(userId);
            if (cached != null) {
                return cached;
            }

            String apps = Settings.Secure.getStringForUser(
                context.getContentResolver(),
                Settings.Secure.HIDE_APPLIST_WHITELIST,
                userId
            );
            Set<String> parsed = parseApps(apps);
            sWhitelistedAppsCache.put(userId, parsed);
            return parsed;
        }
    }

    private static Set<String> parseApps(String apps) {
        if (TextUtils.isEmpty(apps)) {
            return Collections.emptySet();
        }
        Set<String> result = new ArraySet<>();
        for (String app : apps.split(",")) {
            if (!app.isEmpty()) {
                result.add(app);
            }
        }
        return result;
    }
}
