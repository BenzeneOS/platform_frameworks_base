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

import java.util.ArrayList;
import java.util.List;

public final class WakeAttributionEventBatch implements Parcelable {
    public final List<WakeAttributionEvent> events;
    public final long droppedCount;

    public WakeAttributionEventBatch(List<WakeAttributionEvent> events, long droppedCount) {
        this.events = events;
        this.droppedCount = droppedCount;
    }

    private WakeAttributionEventBatch(Parcel in) {
        final List<WakeAttributionEvent> list = new ArrayList<>();
        in.readTypedList(list, WakeAttributionEvent.CREATOR);
        events = list;
        droppedCount = in.readLong();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeTypedList(events);
        dest.writeLong(droppedCount);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<WakeAttributionEventBatch> CREATOR =
            new Parcelable.Creator<>() {
                @Override
                public WakeAttributionEventBatch createFromParcel(Parcel in) {
                    return new WakeAttributionEventBatch(in);
                }

                @Override
                public WakeAttributionEventBatch[] newArray(int size) {
                    return new WakeAttributionEventBatch[size];
                }
            };
}
