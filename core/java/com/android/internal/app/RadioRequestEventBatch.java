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

/**
 * A drained page of radio request events plus the number of records the bounded ring overwrote
 * since the previous drain. A nonzero droppedCount must be surfaced, never swallowed. @hide
 */
public final class RadioRequestEventBatch implements Parcelable {
    public final List<RadioRequestEvent> events;
    public final long droppedCount;

    public RadioRequestEventBatch(List<RadioRequestEvent> events, long droppedCount) {
        this.events = events;
        this.droppedCount = droppedCount;
    }

    private RadioRequestEventBatch(Parcel in) {
        final List<RadioRequestEvent> list = new ArrayList<>();
        in.readTypedList(list, RadioRequestEvent.CREATOR);
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

    public static final Parcelable.Creator<RadioRequestEventBatch> CREATOR =
            new Parcelable.Creator<>() {
                @Override
                public RadioRequestEventBatch createFromParcel(Parcel in) {
                    return new RadioRequestEventBatch(in);
                }

                @Override
                public RadioRequestEventBatch[] newArray(int size) {
                    return new RadioRequestEventBatch[size];
                }
            };
}
