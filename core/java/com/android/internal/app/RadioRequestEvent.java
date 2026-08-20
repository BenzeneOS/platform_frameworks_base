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

package com.android.internal.app;

import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * One radio request transition observed by BatteryStats at event time, carrying the requesting
 * identity that WorkSource-based attribution discards. @hide
 */
public final class RadioRequestEvent implements Parcelable {
    public static final int TYPE_LOCATION_ACTIVE = 1;
    public static final int TYPE_LOCATION_INACTIVE = 2;
    public static final int TYPE_LOCATION_FOREGROUND = 3;
    public static final int TYPE_LOCATION_BACKGROUND = 4;
    public static final int TYPE_WIFI_SCAN_STARTED = 5;
    public static final int TYPE_WIFI_SCAN_STOPPED = 6;
    public static final int TYPE_MOBILE_RADIO_ACTIVE = 7;
    public static final int TYPE_MOBILE_RADIO_INACTIVE = 8;

    public final long sequence;
    public final long elapsedRealtimeMillis;
    public final int type;
    public final int uid;
    @Nullable public final String packageName;
    @Nullable public final String attributionTag;
    public final String provider;
    public final long intervalMillis;
    public final int quality;
    public final boolean foreground;

    public RadioRequestEvent(long sequence, long elapsedRealtimeMillis, int type, int uid,
            @Nullable String packageName, @Nullable String attributionTag, String provider,
            long intervalMillis, int quality, boolean foreground) {
        this.sequence = sequence;
        this.elapsedRealtimeMillis = elapsedRealtimeMillis;
        this.type = type;
        this.uid = uid;
        this.packageName = packageName;
        this.attributionTag = attributionTag;
        this.provider = provider;
        this.intervalMillis = intervalMillis;
        this.quality = quality;
        this.foreground = foreground;
    }

    private RadioRequestEvent(Parcel in) {
        sequence = in.readLong();
        elapsedRealtimeMillis = in.readLong();
        type = in.readInt();
        uid = in.readInt();
        packageName = in.readString();
        attributionTag = in.readString();
        provider = in.readString();
        intervalMillis = in.readLong();
        quality = in.readInt();
        foreground = in.readBoolean();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(sequence);
        dest.writeLong(elapsedRealtimeMillis);
        dest.writeInt(type);
        dest.writeInt(uid);
        dest.writeString(packageName);
        dest.writeString(attributionTag);
        dest.writeString(provider);
        dest.writeLong(intervalMillis);
        dest.writeInt(quality);
        dest.writeBoolean(foreground);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<RadioRequestEvent> CREATOR =
            new Parcelable.Creator<>() {
                @Override
                public RadioRequestEvent createFromParcel(Parcel in) {
                    return new RadioRequestEvent(in);
                }

                @Override
                public RadioRequestEvent[] newArray(int size) {
                    return new RadioRequestEvent[size];
                }
            };
}
