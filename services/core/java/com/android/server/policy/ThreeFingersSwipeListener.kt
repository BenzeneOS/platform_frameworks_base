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

package com.android.server.policy

import android.app.ActivityManager
import android.content.Context
import android.os.RemoteException
import android.util.Slog
import android.view.MotionEvent
import android.view.WindowManagerPolicyConstants.PointerEventListener
import kotlin.math.min

/**
 * Detects three-finger swipe down gestures for triggering screenshots.
 *
 * The gesture is detected when:
 * - Exactly 3 fingers are placed on screen within 500ms
 * - Fingers are roughly horizontally aligned (within 150dp vertical spread)
 * - Fingers are not at the bottom edge of the screen
 * - All 3 fingers swipe down past the threshold (150dp total movement)
 */
class ThreeFingersSwipeListener(
    private val context: Context,
    private val callbacks: Callbacks,
) : PointerEventListener {
    private val pointerIds = IntArray(3)
    private val initMotionY = FloatArray(3)

    private var gestureState: GestureState = GestureState.NONE

    private val density: Float
    private val threshold: Int
    private val threeGestureThreshold: Int
    private val screenHeight: Int
    private val screenWidth: Int

    init {
        val displayMetrics = context.resources.displayMetrics
        density = displayMetrics.density
        threshold = (50.0f * density).toInt()
        threeGestureThreshold = threshold * 3
        screenHeight = displayMetrics.heightPixels
        screenWidth = displayMetrics.widthPixels

        // Reset the setting flag on init
        try {
            ActivityManager.getService().setThreeGestureStateActive(false)
        } catch (e: RemoteException) {
            Slog.e(TAG, "Failed to reset three gesture state", e)
        }
    }

    override fun onPointerEvent(event: MotionEvent) {
        when {
            event.action == MotionEvent.ACTION_DOWN -> {
                changeGestureState(GestureState.NONE)
            }
            gestureState == GestureState.NONE && event.pointerCount == 3 -> {
                if (checkIsStartThreeGesture(event)) {
                    changeGestureState(GestureState.DETECTING)
                    for (i in 0 until 3) {
                        pointerIds[i] = event.getPointerId(i)
                        initMotionY[i] = event.getY(i)
                    }
                } else {
                    changeGestureState(GestureState.NO_DETECT)
                }
            }
        }

        if (gestureState == GestureState.DETECTING) {
            if (event.pointerCount != 3) {
                changeGestureState(GestureState.DETECTED_FALSE)
                return
            }

            if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                var distance = 0.0f
                for (i in 0 until 3) {
                    val index = event.findPointerIndex(pointerIds[i])
                    if (index < 0 || index >= 3) {
                        changeGestureState(GestureState.DETECTED_FALSE)
                        return
                    }
                    distance += event.getY(index) - initMotionY[i]
                }

                if (distance >= threeGestureThreshold) {
                    changeGestureState(GestureState.DETECTED_TRUE)
                    callbacks.onSwipeThreeFingers()
                }
            }
        }
    }

    private fun changeGestureState(state: GestureState) {
        if (gestureState != state) {
            gestureState = state
            val active = gestureState == GestureState.DETECTED_TRUE ||
                         gestureState == GestureState.DETECTING
            try {
                ActivityManager.getService().setThreeGestureStateActive(active)
            } catch (e: RemoteException) {
                Slog.e(TAG, "Failed to set three gesture state", e)
            }
        }
    }

    private fun checkIsStartThreeGesture(event: MotionEvent): Boolean {
        // Check that gesture started within timeout
        if (event.eventTime - event.downTime > GESTURE_START_TIMEOUT_MS) {
            return false
        }

        var minX = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var minY = Float.MAX_VALUE
        var maxY = Float.MIN_VALUE

        for (i in 0 until event.pointerCount) {
            val x = event.getX(i)
            val y = event.getY(i)

            // Don't detect if fingers are too close to bottom edge
            if (y > (screenHeight - threshold).toFloat()) {
                return false
            }

            maxX = maxOf(maxX, x)
            minX = minOf(minX, x)
            maxY = maxOf(maxY, y)
            minY = minOf(minY, y)
        }

        // Check fingers are roughly aligned horizontally (within 150dp vertical spread)
        if (maxY - minY <= density * 150.0f) {
            // Check fingers are within screen width
            return maxX - minX <= min(screenWidth, screenHeight).toFloat()
        }
        return false
    }

    // Using sealed class instead of enum to avoid Kotlin 1.9+ EnumEntriesKt dependency
    private sealed class GestureState {
        object NONE : GestureState()
        object DETECTING : GestureState()
        object DETECTED_FALSE : GestureState()
        object DETECTED_TRUE : GestureState()
        object NO_DETECT : GestureState()
    }

    interface Callbacks {
        fun onSwipeThreeFingers()
    }

    companion object {
        private const val TAG = "ThreeFingersSwipeListener"
        private const val GESTURE_START_TIMEOUT_MS = 500L
    }
}
