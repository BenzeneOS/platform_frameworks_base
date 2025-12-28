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

package com.android.systemui.shade

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.PowerManager
import android.os.UserHandle
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.phone.CentralSurfaces
import javax.inject.Inject

@SysUISingleton
class DoubleTapToSleepGestureListener @Inject constructor(
        private val context: Context,
        private val falsingManager: FalsingManager,
        private val powerManager: PowerManager,
        private val statusBarStateController: StatusBarStateController,
        private val centralSurfaces: CentralSurfaces,
) : GestureDetector.SimpleOnGestureListener() {

    private var doubleTapToSleepStatusBar = false
    private var doubleTapToSleepLockscreen = false
    private val quickQsOffsetHeight: Int

    init {
        val contentObserver = object : ContentObserver(Handler.getMain()) {
            override fun onChange(selfChange: Boolean) {
                doubleTapToSleepStatusBar = Settings.System.getIntForUser(
                        context.contentResolver, Settings.System.DOUBLE_TAP_SLEEP_STATUS_BAR,
                        0, UserHandle.USER_CURRENT) != 0
                doubleTapToSleepLockscreen = Settings.System.getIntForUser(
                        context.contentResolver, Settings.System.DOUBLE_TAP_SLEEP_LOCKSCREEN,
                        0, UserHandle.USER_CURRENT) != 0
            }
        }
        context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.DOUBLE_TAP_SLEEP_STATUS_BAR),
                false, contentObserver, UserHandle.USER_ALL)
        context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.DOUBLE_TAP_SLEEP_LOCKSCREEN),
                false, contentObserver, UserHandle.USER_ALL)
        contentObserver.onChange(true)

        quickQsOffsetHeight = context.resources.getDimensionPixelSize(
                com.android.internal.R.dimen.quick_qs_offset_height)
    }

    override fun onDoubleTapEvent(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_UP) {
            return false
        }

        // Status bar double tap to sleep
        if (doubleTapToSleepStatusBar &&
                !statusBarStateController.isDozing &&
                e.y < quickQsOffsetHeight &&
                !falsingManager.isFalseDoubleTap
        ) {
            powerManager.goToSleep(e.eventTime)
            return true
        }

        // Lockscreen double tap to sleep
        if (doubleTapToSleepLockscreen &&
                !statusBarStateController.isDozing &&
                statusBarStateController.state == StatusBarState.KEYGUARD &&
                !centralSurfaces.isBouncerShowing &&
                !falsingManager.isFalseDoubleTap
        ) {
            powerManager.goToSleep(e.eventTime)
            return true
        }

        return false
    }
}
