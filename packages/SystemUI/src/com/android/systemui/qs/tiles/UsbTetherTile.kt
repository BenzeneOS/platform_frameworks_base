/*
 * Copyright (C) 2015 The Android Open Source Project
 * Copyright (C) 2017-2018 The LineageOS Project
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

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.net.TetheringManager
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile

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

/** USB Tether quick settings tile */
class UsbTetherTile @Inject constructor(
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
    private val mTetheringManager: TetheringManager =
        mContext.getSystemService(TetheringManager::class.java)

    private var mListening = false
    private var mUsbConnected = false
    private var mUsbTetherEnabled = false

    private val mReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            mUsbConnected = intent.getBooleanExtra(UsbManager.USB_CONNECTED, false)
            if (mUsbConnected && mTetheringManager.isTetheringSupported) {
                mUsbTetherEnabled = intent.getBooleanExtra(UsbManager.USB_FUNCTION_RNDIS, false) ||
                    intent.getBooleanExtra(UsbManager.USB_FUNCTION_NCM, false)
            } else {
                mUsbTetherEnabled = false
            }
            refreshState()
        }
    }

    override fun newTileState() = BooleanState()

    override fun handleSetListening(listening: Boolean) {
        if (mListening == listening) return
        mListening = listening

        if (listening) {
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_STATE)
            }
            mContext.registerReceiver(mReceiver, filter)
        } else {
            mContext.unregisterReceiver(mReceiver)
        }
    }

    override fun handleClick(expandable: Expandable?) {
        if (mUsbConnected) {
            mTetheringManager.setUsbTethering(!mUsbTetherEnabled)
        }
    }

    override fun getLongClickIntent(): Intent = Intent(TETHER_SETTINGS)

    override fun isAvailable() = true

    override fun handleUpdateState(state: BooleanState, arg: Any?) {
        state.value = mUsbTetherEnabled
        state.label = mContext.getString(R.string.quick_settings_usb_tether_label)
        if (mIcon == null) {
            mIcon = maybeLoadResourceIcon(R.drawable.ic_qs_usb_tether)
        }
        state.icon = mIcon
        state.state = when {
            !mUsbConnected -> Tile.STATE_UNAVAILABLE
            mUsbTetherEnabled -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        state.secondaryLabel = if (!mUsbConnected) {
            mContext.getString(R.string.quick_settings_usb_tether_no_usb)
        } else {
            null
        }
    }

    override fun getTileLabel(): CharSequence =
        mContext.getString(R.string.quick_settings_usb_tether_label)

    override fun getMetricsCategory() = VIEW_UNKNOWN

    companion object {
        const val TILE_SPEC = "usb_tether"

        private val TETHER_SETTINGS = Intent().setComponent(
            ComponentName(
                "com.android.settings",
                "com.android.settings.TetherSettings"
            )
        )
    }
}
