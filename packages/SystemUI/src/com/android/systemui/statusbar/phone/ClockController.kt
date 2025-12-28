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

package com.android.systemui.statusbar.phone

import android.content.Context
import android.os.UserHandle
import android.provider.Settings
import android.view.View

import com.android.systemui.Dependency
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.Clock
import com.android.systemui.tuner.TunerService

/**
 * Controls the visibility and position of the status bar clock.
 *
 * Supports four clock positions:
 * - Right (0)
 * - Center (1)
 * - Left (2) - default
 * - Hidden (3)
 *
 * @see android.provider.Settings.System.STATUS_BAR_CLOCK
 */
class ClockController(
    private val context: Context,
    statusBar: View,
) : TunerService.Tunable {
    private val centerClock: Clock? = statusBar.findViewById(R.id.clock_center)
    private val leftClock: Clock? = statusBar.findViewById(R.id.clock)
    private val rightClock: Clock? = statusBar.findViewById(R.id.clock_right)

    private val tunerService: TunerService = Dependency.get(TunerService::class.java)

    private var clockPosition: Int = CLOCK_POSITION_LEFT
    var activeClock: Clock? = null
        private set

    init {
        clockPosition = Settings.System.getIntForUser(
            context.contentResolver,
            Settings.System.STATUS_BAR_CLOCK,
            CLOCK_POSITION_LEFT,
            UserHandle.USER_CURRENT
        )

        context.mainExecutor.execute {
            updateActiveClock()
        }

        tunerService.addTunable(this, STATUS_BAR_CLOCK)
    }

    private fun updateActiveClock() {
        // Use setVisibility directly instead of setClockVisibleByUser to work in both
        // legacy mode and when StatusBarRootModernization is enabled
        when (clockPosition) {
            CLOCK_POSITION_RIGHT -> {
                activeClock = rightClock
                leftClock?.visibility = View.GONE
                centerClock?.visibility = View.GONE
                rightClock?.visibility = View.VISIBLE
            }
            CLOCK_POSITION_CENTER -> {
                activeClock = centerClock
                leftClock?.visibility = View.GONE
                rightClock?.visibility = View.GONE
                centerClock?.visibility = View.VISIBLE
            }
            CLOCK_POSITION_HIDE -> {
                activeClock = null
                leftClock?.visibility = View.GONE
                centerClock?.visibility = View.GONE
                rightClock?.visibility = View.GONE
            }
            else -> { // CLOCK_POSITION_LEFT or default
                activeClock = leftClock
                centerClock?.visibility = View.GONE
                rightClock?.visibility = View.GONE
                leftClock?.visibility = View.VISIBLE
            }
        }
    }

    override fun onTuningChanged(key: String, newValue: String?) {
        when (key) {
            STATUS_BAR_CLOCK -> {
                clockPosition = TunerService.parseInteger(newValue, CLOCK_POSITION_LEFT)
                context.mainExecutor.execute {
                    updateActiveClock()
                }
            }
        }
    }

    fun removeTunable() {
        tunerService.removeTunable(this)
    }

    fun onDensityOrFontScaleChanged() {
        activeClock?.onDensityOrFontScaleChanged()
    }

    companion object {
        private const val TAG = "ClockController"

        private const val STATUS_BAR_CLOCK = "system:${Settings.System.STATUS_BAR_CLOCK}"

        const val CLOCK_POSITION_RIGHT = 0
        const val CLOCK_POSITION_CENTER = 1
        const val CLOCK_POSITION_LEFT = 2
        const val CLOCK_POSITION_HIDE = 3
    }
}
