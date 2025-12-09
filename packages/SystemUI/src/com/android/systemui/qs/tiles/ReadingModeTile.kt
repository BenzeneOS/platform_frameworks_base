/*
 * Copyright (C) 2018-2020 The LineageOS Project
 * Copyright (C) 2024 GrapheneOS
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

package com.android.systemui.qs.tiles

import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.view.accessibility.AccessibilityManager

import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.MetricsLogger.VIEW_UNKNOWN
import com.android.systemui.animation.Expandable
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QSTile.BooleanState
import com.android.systemui.plugins.qs.QSTile.Icon
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.res.R

import javax.inject.Inject

/** Quick settings tile: Reading Mode (Grayscale) */
class ReadingModeTile @Inject constructor(
    host: QSHost,
    uiEventLogger: QsEventLogger,
    @Background backgroundLooper: Looper,
    @Main mainHandler: Handler,
    falsingManager: FalsingManager,
    metricsLogger: MetricsLogger,
    statusBarStateController: StatusBarStateController,
    activityStarter: ActivityStarter,
    qsLogger: QSLogger
) : QSTileImpl<BooleanState>(
    host, uiEventLogger, backgroundLooper, mainHandler, falsingManager,
    metricsLogger, statusBarStateController, activityStarter, qsLogger
) {
    private var mIcon: Icon? = null
    private var mListening = false

    private val mObserver = object : ContentObserver(mHandler) {
        override fun onChange(selfChange: Boolean) {
            refreshState()
        }
    }

    override fun newTileState() = BooleanState()

    override fun handleSetListening(listening: Boolean) {
        if (mListening == listening) return
        mListening = listening

        if (listening) {
            mContext.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED),
                false, mObserver
            )
            mContext.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER),
                false, mObserver
            )
        } else {
            mContext.contentResolver.unregisterContentObserver(mObserver)
        }
    }

    override fun handleClick(expandable: Expandable?) {
        val newState = !isReadingModeEnabled()
        setReadingModeEnabled(newState)
        refreshState()
    }

    override fun getLongClickIntent(): Intent = DISPLAY_SETTINGS

    override fun isAvailable() = true

    override fun handleUpdateState(state: BooleanState, arg: Any?) {
        state.value = isReadingModeEnabled()
        if (mIcon == null) {
            mIcon = maybeLoadResourceIcon(R.drawable.ic_qs_reading_mode)
        }
        state.icon = mIcon
        state.label = tileLabel
        if (state.value) {
            state.contentDescription = mContext.getString(
                R.string.accessibility_quick_settings_reading_mode_on
            )
            state.state = Tile.STATE_ACTIVE
        } else {
            state.contentDescription = mContext.getString(
                R.string.accessibility_quick_settings_reading_mode_off
            )
            state.state = Tile.STATE_INACTIVE
        }
    }

    override fun getTileLabel(): CharSequence =
        mContext.getString(R.string.quick_settings_reading_mode)

    override fun getMetricsCategory() = VIEW_UNKNOWN

    private fun isReadingModeEnabled(): Boolean {
        val daltonizerEnabled = Settings.Secure.getInt(
            mContext.contentResolver,
            Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED, 0
        )
        val daltonizerMode = Settings.Secure.getInt(
            mContext.contentResolver,
            Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER, -1
        )
        return daltonizerEnabled == 1 && daltonizerMode == DALTONIZER_GRAYSCALE
    }

    private fun setReadingModeEnabled(enabled: Boolean) {
        if (enabled) {
            // Enable daltonizer with grayscale mode
            Settings.Secure.putInt(
                mContext.contentResolver,
                Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER, DALTONIZER_GRAYSCALE
            )
            Settings.Secure.putInt(
                mContext.contentResolver,
                Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED, 1
            )
        } else {
            // Disable daltonizer
            Settings.Secure.putInt(
                mContext.contentResolver,
                Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED, 0
            )
        }
    }

    companion object {
        const val TILE_SPEC = "reading_mode"

        private val DISPLAY_SETTINGS = Intent(Settings.ACTION_DISPLAY_SETTINGS)

        // Daltonizer mode for grayscale (monochromacy simulation)
        private const val DALTONIZER_GRAYSCALE =
            AccessibilityManager.DALTONIZER_SIMULATE_MONOCHROMACY
    }
}
