/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.server.location.injector;

import android.location.LocationRequest;
import android.location.util.identity.CallerIdentity;
import android.os.BatteryStatsInternal;

import com.android.server.LocalServices;

import android.util.ArrayMap;

import java.util.ArrayList;
import java.util.Map;

/**
 * Forwards per-registration location request transitions to BatteryStats with the full requesting
 * identity, which the WorkSource path collapses to a bare uid. Runs under the multiplexer lock,
 * so it must stay cheap and must never call back into the location stack.
 *
 * Live registrations are also tracked here so a collection session that starts while requests are
 * already active can be given their state. Transitions alone would leave such a session blind
 * until something happened to toggle, which is the common case rather than a corner one.
 */
public final class LocationBatteryStatsReporter {
    private static final Object sLock = new Object();
    private static final Map<String, ActiveRequest> sActiveRequests = new ArrayMap<>();
    private static volatile BatteryStatsInternal sBatteryStats;

    private LocationBatteryStatsReporter() {}

    /** Registered here, not lazily, so a session starting before any transition can snapshot. */
    public static void onSystemReady() {
        BatteryStatsInternal batteryStats = batteryStats();
        if (batteryStats != null) {
            batteryStats.setLocationRequestSnapshotCallback(
                    LocationBatteryStatsReporter::startCollecting);
        }
    }

    /** Starts collection and replays live requests with no transition slipping between. */
    private static void startCollecting(Runnable enableCollection) {
        synchronized (sLock) {
            enableCollection.run();
            replayActiveRequests();
        }
    }

    /** Identity must not outlive the registration, even with no inactive transition. */
    public static void forget(CallerIdentity identity, String provider) {
        synchronized (sLock) {
            sActiveRequests.remove(key(identity, provider));
        }
    }

    private static final class ActiveRequest {
        final int uid;
        final String packageName;
        final String attributionTag;
        final String provider;
        final String registrationId;
        final long intervalMillis;
        final int quality;
        volatile boolean foreground;

        ActiveRequest(int uid, String packageName, String attributionTag, String provider,
                String registrationId, long intervalMillis, int quality, boolean foreground) {
            this.uid = uid;
            this.packageName = packageName;
            this.attributionTag = attributionTag;
            this.provider = provider;
            this.registrationId = registrationId;
            this.intervalMillis = intervalMillis;
            this.quality = quality;
            this.foreground = foreground;
        }
    }

    public static void reportActive(String provider, CallerIdentity identity,
            LocationRequest request, boolean foreground) {
        report(BatteryStatsInternal.LOCATION_REQUEST_ACTIVE, provider, identity, request,
                foreground);
    }

    public static void reportInactive(String provider, CallerIdentity identity,
            LocationRequest request, boolean foreground) {
        report(BatteryStatsInternal.LOCATION_REQUEST_INACTIVE, provider, identity, request,
                foreground);
    }

    public static void reportGnssListenerActive(String provider, CallerIdentity identity,
            boolean foreground) {
        reportRaw(BatteryStatsInternal.LOCATION_REQUEST_ACTIVE, provider, identity, -1L, 0,
                foreground);
    }

    public static void reportGnssListenerInactive(String provider, CallerIdentity identity,
            boolean foreground) {
        reportRaw(BatteryStatsInternal.LOCATION_REQUEST_INACTIVE, provider, identity, -1L, 0,
                foreground);
    }

    public static void reportForegroundChanged(String provider, CallerIdentity identity,
            LocationRequest request, boolean foreground) {
        report(foreground
                        ? BatteryStatsInternal.LOCATION_REQUEST_FOREGROUND
                        : BatteryStatsInternal.LOCATION_REQUEST_BACKGROUND,
                provider, identity, request, foreground);
    }

    private static void report(int eventType, String provider, CallerIdentity identity,
            LocationRequest request, boolean foreground) {
        reportRaw(eventType, provider, identity, request.getIntervalMillis(),
                request.getQuality(), foreground);
    }

    private static void reportRaw(int eventType, String provider, CallerIdentity identity,
            long intervalMillis, int quality, boolean foreground) {
        final String key = key(identity, provider);
        synchronized (sLock) {
            switch (eventType) {
                case BatteryStatsInternal.LOCATION_REQUEST_ACTIVE:
                    sActiveRequests.put(key, new ActiveRequest(identity.getUid(),
                            identity.getPackageName(), identity.getAttributionTag(), provider,
                            key, intervalMillis, quality, foreground));
                    break;
                case BatteryStatsInternal.LOCATION_REQUEST_INACTIVE:
                    sActiveRequests.remove(key);
                    break;
                default:
                    final ActiveRequest tracked = sActiveRequests.get(key);
                    if (tracked != null) {
                        tracked.foreground = foreground;
                    }
                    break;
            }

            BatteryStatsInternal batteryStats = batteryStats();
            if (batteryStats == null) return;
            batteryStats.noteLocationRequestStateChanged(eventType, identity.getUid(),
                    identity.getPackageName(), identity.getAttributionTag(), provider, key,
                    intervalMillis, quality, foreground);
        }
    }

    private static void replayActiveRequests() {
        synchronized (sLock) {
            BatteryStatsInternal batteryStats = batteryStats();
            if (batteryStats == null) return;
            for (ActiveRequest request : new ArrayList<>(sActiveRequests.values())) {
                batteryStats.noteLocationRequestStateChanged(
                        BatteryStatsInternal.LOCATION_REQUEST_ACTIVE, request.uid,
                        request.packageName, request.attributionTag, request.provider,
                        request.registrationId, request.intervalMillis, request.quality,
                        request.foreground);
            }
        }
    }

    private static BatteryStatsInternal batteryStats() {
        BatteryStatsInternal batteryStats = sBatteryStats;
        if (batteryStats == null) {
            batteryStats = LocalServices.getService(BatteryStatsInternal.class);
            if (batteryStats == null) return null;
            sBatteryStats = batteryStats;
        }
        return batteryStats;
    }

    private static String key(CallerIdentity identity, String provider) {
        return identity.getUid() + "|" + identity.getPackageName() + "|"
                + identity.getAttributionTag() + "|" + provider + "|" + identity.getListenerId();
    }
}
