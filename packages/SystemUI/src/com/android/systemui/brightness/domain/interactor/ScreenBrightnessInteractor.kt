/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.android.settingslib.display.BrightnessUtils
import com.android.systemui.brightness.data.repository.ScreenBrightnessRepository
import com.android.systemui.brightness.shared.model.BrightnessLog
import com.android.systemui.brightness.shared.model.GammaBrightness
import com.android.systemui.brightness.shared.model.LinearBrightness
import com.android.systemui.brightness.shared.model.logDiffForTable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.plugins.ActivityStarter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Converts between [GammaBrightness] and [LinearBrightness].
 *
 * @see BrightnessUtils
 */
@SysUISingleton
class ScreenBrightnessInteractor
@Inject
constructor(
    private val screenBrightnessRepository: ScreenBrightnessRepository,
    @Application private val applicationScope: CoroutineScope,
    @BrightnessLog private val tableBuffer: TableLogBuffer,
    private val activityStarter: ActivityStarter,
    private val context: Context,
) {
    /** Maximum value in the Gamma space for brightness */
    val maxGammaBrightness = GammaBrightness(BrightnessUtils.GAMMA_SPACE_MAX)

    /** Minimum value in the Gamma space for brightness */
    val minGammaBrightness = GammaBrightness(BrightnessUtils.GAMMA_SPACE_MIN)

    /**
     * Brightness in the Gamma space for the current display. It will always represent a value
     * between [minGammaBrightness] and [maxGammaBrightness]
     */
    val gammaBrightness: Flow<GammaBrightness> =
        with(screenBrightnessRepository) {
            combine(
                    linearBrightness,
                    minLinearBrightness,
                    maxLinearBrightness,
                ) { brightness, min, max ->
                    brightness.toGammaBrightness(min, max)
                }
                .logDiffForTable(tableBuffer, TABLE_PREFIX_GAMMA, TABLE_COLUMN_BRIGHTNESS, null)
                .stateIn(applicationScope, SharingStarted.WhileSubscribed(), GammaBrightness(0))
        }

    val brightnessOverriddenByWindow = screenBrightnessRepository.isBrightnessOverriddenByWindow

    /** Whether automatic brightness mode is enabled */
    val isAutomaticBrightnessEnabled = screenBrightnessRepository.isAutomaticBrightnessEnabled

    /** Whether automatic brightness is available on this device */
    val isAutomaticBrightnessAvailable = screenBrightnessRepository.isAutomaticBrightnessAvailable

    /** Whether the auto brightness button should be shown in QS */
    val showAutoBrightnessButton = screenBrightnessRepository.showAutoBrightnessButton

    /** Toggles automatic brightness mode on/off */
    fun toggleAutomaticBrightness() {
        screenBrightnessRepository.toggleAutomaticBrightness()
    }

    /** Opens status bar settings and dismisses the shade */
    fun openStatusBarSettings() {
        val intent = Intent().apply {
            setClassName("com.android.settings", "com.android.settings.Settings\$StatusBarSettingsActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // Fallback to display settings if StatusBarSettingsActivity doesn't exist
        val fallbackIntent = Intent(Settings.ACTION_DISPLAY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val targetIntent = if (intent.resolveActivity(context.packageManager) != null) {
            intent
        } else {
            fallbackIntent
        }
        activityStarter.postStartActivityDismissingKeyguard(targetIntent, 0)
    }

    /** Sets the brightness temporarily, while the user is changing it. */
    suspend fun setTemporaryBrightness(gammaBrightness: GammaBrightness) {
        screenBrightnessRepository.setTemporaryBrightness(
            gammaBrightness.clamp().toLinearBrightness()
        )
    }

    /** Sets the brightness definitely. */
    suspend fun setBrightness(gammaBrightness: GammaBrightness) {
        screenBrightnessRepository.setBrightness(gammaBrightness.clamp().toLinearBrightness())
    }

    private suspend fun GammaBrightness.toLinearBrightness(): LinearBrightness {
        val bounds = screenBrightnessRepository.getMinMaxLinearBrightness()
        return LinearBrightness(
            BrightnessUtils.convertGammaToLinearFloat(
                value,
                bounds.first.floatValue,
                bounds.second.floatValue
            )
        )
    }

    private fun GammaBrightness.clamp(): GammaBrightness {
        return GammaBrightness(value.coerceIn(minGammaBrightness.value, maxGammaBrightness.value))
    }

    private fun LinearBrightness.toGammaBrightness(
        min: LinearBrightness,
        max: LinearBrightness,
    ): GammaBrightness {
        return GammaBrightness(
            BrightnessUtils.convertLinearToGammaFloat(floatValue, min.floatValue, max.floatValue)
        )
    }

    private companion object {
        const val TABLE_COLUMN_BRIGHTNESS = "brightness"
        const val TABLE_PREFIX_GAMMA = "gamma"
    }
}
