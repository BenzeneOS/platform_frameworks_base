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

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Candidates frozen after the delayed CpuWakeupStats settlement window. They are temporal
 * correlations, not proof that an app caused the wakeup. @hide
 */
public final class WakeAttributionEvent implements Parcelable {
    public final long elapsedRealtimeMillis;
    public final long uptimeMillis;
    public final String reason;
    public final int[] subsystems;
    public final int[][] candidateUids;
    public final boolean incomplete;

    public WakeAttributionEvent(long elapsedRealtimeMillis, long uptimeMillis, String reason,
            int[] subsystems, int[][] candidateUids, boolean incomplete) {
        if (subsystems.length != candidateUids.length) {
            throw new IllegalArgumentException("Subsystem and uid arrays must have equal lengths");
        }
        this.elapsedRealtimeMillis = elapsedRealtimeMillis;
        this.uptimeMillis = uptimeMillis;
        this.reason = Objects.requireNonNull(reason);
        this.subsystems = subsystems;
        this.candidateUids = candidateUids;
        this.incomplete = incomplete;
    }

    private WakeAttributionEvent(Parcel in) {
        elapsedRealtimeMillis = in.readLong();
        uptimeMillis = in.readLong();
        reason = Objects.requireNonNull(in.readString());
        final int count = in.readInt();
        subsystems = new int[count];
        candidateUids = new int[count][];
        for (int i = 0; i < count; i++) {
            subsystems[i] = in.readInt();
            candidateUids[i] = in.createIntArray();
        }
        incomplete = in.readBoolean();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(elapsedRealtimeMillis);
        dest.writeLong(uptimeMillis);
        dest.writeString(reason);
        dest.writeInt(subsystems.length);
        for (int i = 0; i < subsystems.length; i++) {
            dest.writeInt(subsystems[i]);
            dest.writeIntArray(candidateUids[i]);
        }
        dest.writeBoolean(incomplete);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<WakeAttributionEvent> CREATOR =
            new Parcelable.Creator<>() {
                @Override
                public WakeAttributionEvent createFromParcel(Parcel in) {
                    return new WakeAttributionEvent(in);
                }

                @Override
                public WakeAttributionEvent[] newArray(int size) {
                    return new WakeAttributionEvent[size];
                }
            };
}
