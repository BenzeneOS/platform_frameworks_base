/*
 * Copyright (C) 2025 Amaan Qureshi <contact@amaanq.com>
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

package com.android.systemui.brightness.domain.interactor

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Tracks and toggles automatic brightness mode for the QS brightness slider button. */
@SysUISingleton
class AutoBrightnessInteractor
@Inject
constructor(
    private val contentResolver: ContentResolver,
    private val context: Context,
    @Main mainHandler: Handler,
    private val activityStarter: ActivityStarter,
) {
    /** Whether automatic brightness is available on this device */
    val isAutomaticBrightnessAvailable: Boolean =
        context.resources.getBoolean(
            com.android.internal.R.bool.config_automatic_brightness_available
        )

    private val _isAutomaticBrightnessEnabled = MutableStateFlow(readBrightnessMode())

    /** Whether automatic brightness mode is enabled */
    val isAutomaticBrightnessEnabled: StateFlow<Boolean> = _isAutomaticBrightnessEnabled

    private val _showAutoBrightnessButton = MutableStateFlow(readShowButton())

    /** Whether the auto brightness button should be shown in QS */
    val showAutoBrightnessButton: StateFlow<Boolean> = _showAutoBrightnessButton

    init {
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE),
            false,
            object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean) {
                    _isAutomaticBrightnessEnabled.value = readBrightnessMode()
                }
            },
            UserHandle.USER_ALL,
        )
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.QS_SHOW_AUTO_BRIGHTNESS),
            false,
            object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean) {
                    _showAutoBrightnessButton.value = readShowButton()
                }
            },
            UserHandle.USER_ALL,
        )
    }

    private fun readBrightnessMode(): Boolean =
        Settings.System.getIntForUser(
            contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            UserHandle.USER_CURRENT,
        ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC

    private fun readShowButton(): Boolean =
        Settings.Secure.getInt(contentResolver, Settings.Secure.QS_SHOW_AUTO_BRIGHTNESS, 1) == 1

    /** Toggles automatic brightness mode on/off */
    fun toggleAutomaticBrightness() {
        val newMode =
            if (_isAutomaticBrightnessEnabled.value) {
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            } else {
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            }
        Settings.System.putIntForUser(
            contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            newMode,
            UserHandle.USER_CURRENT,
        )
    }

    /** Opens status bar settings and dismisses the shade */
    fun openStatusBarSettings() {
        val intent =
            Intent().apply {
                setClassName(
                    "com.android.settings",
                    "com.android.settings.Settings\$StatusBarSettingsActivity",
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        // Fallback to display settings if StatusBarSettingsActivity doesn't exist
        val fallbackIntent =
            Intent(Settings.ACTION_DISPLAY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        val targetIntent =
            if (intent.resolveActivity(context.packageManager) != null) intent else fallbackIntent
        activityStarter.postStartActivityDismissingKeyguard(targetIntent, 0)
    }
}
