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

import android.content.pm.GosPackageState;
import android.content.pm.GosPackageStateFlag;

public final class PushCompatHooks {
    private static final int FLAG_SPOOF_GMS_AVAILABILITY = 1;

    private static int flags;

    private PushCompatHooks() {}

    public static int getFlags(GosPackageState gosPs) {
        return gosPs.hasFlag(GosPackageStateFlag.PUSH_COMPAT_RELAY) ?
                FLAG_SPOOF_GMS_AVAILABILITY :
                0;
    }

    public static void setFlags(int v) {
        flags = v;
    }

    public static boolean shouldSpoofGmsAvailability() {
        return (flags & FLAG_SPOOF_GMS_AVAILABILITY) != 0;
    }
}
