/*
 * Copyright (C) 2026 Amaan Qureshi <contact@amaanq.com>
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

package com.android.internal.pushcompat;

import android.annotation.Nullable;
import android.content.pm.ResolveInfo;

import java.util.List;
import java.util.Locale;

public final class FirebaseReceiverResolver {
    private FirebaseReceiverResolver() {}

    public static @Nullable ResolveInfo selectReceiver(List<ResolveInfo> candidates) {
        ResolveInfo selected = null;
        for (ResolveInfo candidate : candidates) {
            if (candidate == null || candidate.activityInfo == null
                    || candidate.activityInfo.name == null) {
                continue;
            }
            if (selected == null || compare(candidate, selected) < 0) {
                selected = candidate;
            }
        }
        return selected;
    }

    private static int compare(ResolveInfo left, ResolveInfo right) {
        final int rankComparison = Integer.compare(rank(left), rank(right));
        if (rankComparison != 0) {
            return rankComparison;
        }
        return left.activityInfo.name.compareTo(right.activityInfo.name);
    }

    private static int rank(ResolveInfo candidate) {
        final String name = candidate.activityInfo.name;
        if (name.endsWith("FirebaseInstanceIdReceiver")) {
            return 0;
        }
        final String normalizedName = name.toLowerCase(Locale.ROOT);
        if (!normalizedName.contains("analytics") && !normalizedName.contains("measurement")) {
            return 1;
        }
        return 2;
    }
}
