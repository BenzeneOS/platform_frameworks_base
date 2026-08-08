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

package com.android.systemui.qs.panels.shared.model

import android.provider.Settings

/** User-selected shape behavior for Quick Settings tiles. */
enum class QSTileShape(val settingValue: Int) {
    BOTH(Settings.Secure.QS_TILE_SHAPE_BOTH),
    ROUNDED(Settings.Secure.QS_TILE_SHAPE_ROUNDED),
    SQUARE(Settings.Secure.QS_TILE_SHAPE_SQUARE);

    companion object {
        fun fromSetting(value: Int): QSTileShape {
            return entries.firstOrNull { it.settingValue == value } ?: BOTH
        }
    }
}
