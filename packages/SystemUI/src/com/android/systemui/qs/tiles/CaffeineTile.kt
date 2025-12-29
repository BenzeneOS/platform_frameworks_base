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

package com.android.systemui.qs.tiles

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.os.BatteryManager
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.android.internal.jank.InteractionJankMonitor
import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.MetricsLogger.VIEW_UNKNOWN
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogTransitionAnimator
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
import com.android.systemui.shade.domain.interactor.ShadeDialogContextInteractor
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import javax.inject.Inject

class CaffeineTile @Inject constructor(
    host: QSHost,
    uiEventLogger: QsEventLogger,
    @Background backgroundLooper: Looper,
    @Main mainHandler: Handler,
    falsingManager: FalsingManager,
    metricsLogger: MetricsLogger,
    statusBarStateController: StatusBarStateController,
    activityStarter: ActivityStarter,
    qsLogger: QSLogger,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
    private val systemUIDialogFactory: SystemUIDialog.Factory,
    private val shadeDialogContextInteractor: ShadeDialogContextInteractor
) : QSTileImpl<BooleanState>(
    host, uiEventLogger, backgroundLooper, mainHandler,
    falsingManager, metricsLogger, statusBarStateController, activityStarter, qsLogger
) {
    companion object {
        const val TILE_SPEC = "caffeine"
        private const val QUICK_CLICK_THRESHOLD_MS = 5000L
        private const val SLIDER_MAX = 121f // 121 = infinite, allows selecting exactly 120 min

    }

    private enum class DurationMode(val seconds: Int) {
        FIVE_MIN(5 * 60),
        TEN_MIN(10 * 60),
        THIRTY_MIN(30 * 60),
        INFINITE(-1),
        UNTIL_UNPLUGGED(-2);

        fun next(): DurationMode? = when (this) {
            FIVE_MIN -> TEN_MIN
            TEN_MIN -> THIRTY_MIN
            THIRTY_MIN -> INFINITE
            else -> null
        }

        companion object {
            val DEFAULT = FIVE_MIN
        }
    }

    private sealed class CaffeineState {
        object Off : CaffeineState()
        data class Timed(val mode: DurationMode, val isCustom: Boolean = false) : CaffeineState()
        object UntilUnplugged : CaffeineState()

        val isActive: Boolean get() = this !is Off
    }

    private val wakeLock: PowerManager.WakeLock =
        mContext.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.FULL_WAKE_LOCK, "SystemUI:CaffeineTile")

    private var icon: Icon? = null
    @Volatile private var secondsRemaining = 0
    private var countdownTimer: CountDownTimer? = null
    @Volatile private var lastClickTime = -1L
    @Volatile private var caffeineState: CaffeineState = CaffeineState.Off

    private val dialogController = CaffeineDialogController()
    private val receiver = PowerReceiver()

    init {
        receiver.register()
    }

    override fun newTileState(): BooleanState = BooleanState()

    override fun handleDestroy() {
        super.handleDestroy()
        stopCountDown()
        receiver.unregister()
        if (wakeLock.isHeld) wakeLock.release()
    }

    override fun handleSetListening(listening: Boolean) {}

    override fun isAvailable(): Boolean = true

    override fun handleClick(expandable: Expandable?) {
        when {
            shouldCycleDuration() -> cycleDuration()
            wakeLock.isHeld -> turnOff()
            else -> turnOn(DurationMode.DEFAULT)
        }
        lastClickTime = SystemClock.elapsedRealtime()
        refreshState()
    }

    override fun handleLongClick(expandable: Expandable?) {
        mUiHandler.post { dialogController.show(expandable) }
    }

    override fun getLongClickIntent(): Intent? = null

    override fun getTileLabel(): CharSequence =
        mContext.getString(R.string.quick_settings_caffeine_label)

    override fun getMetricsCategory(): Int = VIEW_UNKNOWN

    override fun handleUpdateState(state: BooleanState, arg: Any?) {
        state.value = wakeLock.isHeld
        state.icon = icon ?: maybeLoadResourceIcon(R.drawable.ic_qs_caffeine).also { icon = it }
        state.handlesLongClick = true
        state.label = mContext.getString(R.string.quick_settings_caffeine_label)

        if (state.value) {
            state.secondaryLabel = formatRemainingTime()
            state.contentDescription =
                mContext.getString(R.string.accessibility_quick_settings_caffeine_on)
            state.state = Tile.STATE_ACTIVE
        } else {
            state.secondaryLabel = null
            state.contentDescription =
                mContext.getString(R.string.accessibility_quick_settings_caffeine_off)
            state.state = Tile.STATE_INACTIVE
        }
    }

    private fun shouldCycleDuration(): Boolean {
        val state = caffeineState
        return wakeLock.isHeld &&
            lastClickTime != -1L &&
            SystemClock.elapsedRealtime() - lastClickTime < QUICK_CLICK_THRESHOLD_MS &&
            state is CaffeineState.Timed &&
            !state.isCustom
    }

    private fun cycleDuration() {
        val current = (caffeineState as? CaffeineState.Timed)?.mode ?: return
        val next = current.next()
        if (next != null) {
            turnOn(next)
        } else {
            turnOff()
        }
    }

    private fun turnOn(mode: DurationMode, isCustom: Boolean = false) {
        if (!wakeLock.isHeld) wakeLock.acquire()
        caffeineState = CaffeineState.Timed(mode, isCustom)
        startCountDown(mode.seconds.toLong())
    }

    private fun turnOnUntilUnplugged() {
        if (!isCharging()) return
        if (!wakeLock.isHeld) wakeLock.acquire()
        caffeineState = CaffeineState.UntilUnplugged
        secondsRemaining = DurationMode.UNTIL_UNPLUGGED.seconds
        stopCountDown()
        refreshState()
    }

    private fun turnOnCustomDuration(minutes: Int) {
        if (!wakeLock.isHeld) wakeLock.acquire()
        caffeineState = CaffeineState.Timed(DurationMode.FIVE_MIN, isCustom = true)
        startCountDown(minutes * 60L)
        refreshState()
    }

    private fun turnOff() {
        if (wakeLock.isHeld) wakeLock.release()
        stopCountDown()
        caffeineState = CaffeineState.Off
    }

    private fun startCountDown(durationSeconds: Long) {
        stopCountDown()
        secondsRemaining = durationSeconds.toInt()

        if (durationSeconds < 0) return // Infinite or until unplugged

        countdownTimer = object : CountDownTimer(durationSeconds * 1000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                secondsRemaining = (millisUntilFinished / 1000).toInt()
                refreshState()
            }

            override fun onFinish() {
                if (wakeLock.isHeld) wakeLock.release()
                caffeineState = CaffeineState.Off
                refreshState()
            }
        }.start()
    }

    private fun stopCountDown() {
        countdownTimer?.cancel()
        countdownTimer = null
    }

    private fun formatRemainingTime(): String = when (secondsRemaining) {
        DurationMode.UNTIL_UNPLUGGED.seconds ->
            mContext.getString(R.string.quick_settings_caffeine_until_unplugged_indicator)
        DurationMode.INFINITE.seconds ->
            mContext.getString(R.string.quick_settings_caffeine_infinite_indicator)
        else -> {
            val h = secondsRemaining / 3600
            val m = (secondsRemaining % 3600) / 60
            val s = secondsRemaining % 60
            if (h > 0) "$h:%02d:%02d".format(m, s) else "%d:%02d".format(m, s)
        }
    }

    private fun isCharging(): Boolean {
        val intent = mContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    private enum class Selection { DURATION, WHILE_CHARGING, OFF, NONE }

    private data class DialogViews(
        val durationLabel: TextView,
        val slider: Slider,
        val durationCard: MaterialCardView,
        val unpluggedCard: MaterialCardView,
        val offCard: MaterialCardView
    ) {
        companion object {
            fun from(layout: View) = DialogViews(
                durationLabel = layout.findViewById(R.id.caffeine_duration_label),
                slider = layout.findViewById(R.id.caffeine_slider),
                durationCard = layout.findViewById(R.id.caffeine_duration_card),
                unpluggedCard = layout.findViewById(R.id.caffeine_unplugged_card),
                offCard = layout.findViewById(R.id.caffeine_off_card)
            )
        }
    }

    private inner class CaffeineDialogController {
        private var dialog: SystemUIDialog? = null
        private var views: DialogViews? = null

        private var selection = Selection.NONE
        private var hasUserSelection = false

        fun show(expandable: Expandable?) {
            val layout = LayoutInflater.from(mContext).inflate(R.layout.caffeine_dialog, null)
            val v = DialogViews.from(layout)
            views = v

            initializeState(v)
            setupListeners(v)

            val d = systemUIDialogFactory.create(shadeDialogContextInteractor.context).apply {
                setTitle(R.string.quick_settings_caffeine_label)
                setView(layout)
                setPositiveButton(R.string.quick_settings_caffeine_done) { _, _ ->
                    val sliderValue = v.slider.value
                    mHandler.post { applySelection(sliderValue) }
                }
                setShowForAllUsers(true)
                setOnDismissListener {
                    dialog = null
                    views = null
                }
            }
            dialog = d

            val controller = expandable?.dialogTransitionController(
                DialogCuj(InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN, "caffeine_dialog")
            )
            if (controller != null) {
                dialogTransitionAnimator.show(d, controller)
            } else {
                d.show()
            }

            d.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.isAllCaps = false
        }

        private fun initializeState(v: DialogViews) {
            val isActive = wakeLock.isHeld
            selection = when {
                !isActive -> Selection.OFF
                caffeineState is CaffeineState.UntilUnplugged -> Selection.WHILE_CHARGING
                else -> Selection.DURATION
            }
            hasUserSelection = true

            if (selection == Selection.DURATION && isActive) {
                when (secondsRemaining) {
                    DurationMode.INFINITE.seconds -> {
                        v.slider.value = SLIDER_MAX
                        v.durationLabel.text = "∞"
                    }
                    in 1..Int.MAX_VALUE -> {
                        val mins = (secondsRemaining + 59) / 60
                        v.slider.value = minOf(mins.toFloat(), SLIDER_MAX - 1)
                        v.durationLabel.text = "${mins}m"
                    }
                }
            }

            updateCards(v, isActive)
        }

        private fun setupListeners(v: DialogViews) {
            val isActive = wakeLock.isHeld

            v.slider.setLabelFormatter { value ->
                if (value >= SLIDER_MAX) "∞" else "${value.toInt()}m"
            }

            v.slider.addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    selection = Selection.DURATION
                    hasUserSelection = true
                    v.durationLabel.text = if (value >= SLIDER_MAX) "∞" else "${value.toInt()}m"
                    updateCards(v, isActive)
                }
            }

            v.durationCard.setOnClickListener {
                selection = Selection.DURATION
                hasUserSelection = true
                updateCards(v, isActive)
            }

            v.unpluggedCard.setOnClickListener {
                if (!isCharging()) {
                    updateCards(v, isActive)
                    return@setOnClickListener
                }
                selection = Selection.WHILE_CHARGING
                hasUserSelection = true
                updateCards(v, isActive)
            }

            v.offCard.setOnClickListener {
                // Allow selecting OFF if caffeine is active OR if user has selected another option
                if (!isActive && selection == Selection.OFF) return@setOnClickListener
                selection = Selection.OFF
                hasUserSelection = true
                updateCards(v, isActive)
            }
        }

        private fun updateCards(v: DialogViews, caffeineActive: Boolean) {
            val charging = isCharging()
            // OFF card is enabled if caffeine is active OR user selected another option
            val offEnabled = caffeineActive || selection != Selection.OFF

            v.durationCard.isChecked = selection == Selection.DURATION
            v.unpluggedCard.isChecked = selection == Selection.WHILE_CHARGING
            v.offCard.isChecked = selection == Selection.OFF

            updateDurationCard(v, selection == Selection.DURATION)
            updateOptionCard(v.unpluggedCard, selection == Selection.WHILE_CHARGING, charging)
            updateOptionCard(v.offCard, selection == Selection.OFF, offEnabled)
        }

        private fun updateDurationCard(v: DialogViews, selected: Boolean) {
            if (selected) {
                v.durationCard.setCardBackgroundColor(mContext.getColor(android.R.color.system_accent1_200))
                v.durationLabel.setTextColor(mContext.getColor(android.R.color.system_accent1_900))
                v.slider.apply {
                    thumbTintList = ColorStateList.valueOf(mContext.getColor(android.R.color.system_accent1_600))
                    trackActiveTintList = ColorStateList.valueOf(mContext.getColor(android.R.color.system_accent1_600))
                    trackInactiveTintList = ColorStateList.valueOf(mContext.getColor(android.R.color.system_accent1_100))
                    tickActiveTintList = ColorStateList.valueOf(mContext.getColor(android.R.color.system_accent1_400))
                    tickInactiveTintList = ColorStateList.valueOf(mContext.getColor(android.R.color.system_accent1_400))
                }
            } else {
                v.durationCard.setCardBackgroundColor(mContext.getColor(android.R.color.system_neutral2_900))
                v.durationLabel.setTextColor(mContext.getColor(android.R.color.system_neutral2_300))
                v.slider.apply {
                    thumbTintList = ColorStateList.valueOf(mContext.getColor(android.R.color.system_neutral2_300))
                    trackActiveTintList = ColorStateList.valueOf(mContext.getColor(android.R.color.system_neutral2_300))
                    trackInactiveTintList = ColorStateList.valueOf(0x30FFFFFF)
                    tickActiveTintList = ColorStateList.valueOf(mContext.getColor(android.R.color.system_neutral2_500))
                    tickInactiveTintList = ColorStateList.valueOf(mContext.getColor(android.R.color.system_neutral2_500))
                }
            }
        }

        private fun updateOptionCard(card: MaterialCardView, selected: Boolean, enabled: Boolean) {
            val text = card.getChildAt(0) as? TextView ?: return
            when {
                selected -> {
                    card.setCardBackgroundColor(mContext.getColor(android.R.color.system_accent1_200))
                    text.setTextColor(mContext.getColor(android.R.color.system_accent1_900))
                    card.alpha = 1f
                }
                !enabled -> {
                    card.setCardBackgroundColor(mContext.getColor(android.R.color.system_neutral2_900))
                    text.setTextColor(mContext.getColor(android.R.color.system_neutral2_500))
                    card.alpha = 0.5f
                }
                else -> {
                    card.setCardBackgroundColor(mContext.getColor(android.R.color.system_neutral2_900))
                    text.setTextColor(mContext.getColor(android.R.color.system_neutral2_300))
                    card.alpha = 1f
                }
            }
        }

        private fun applySelection(sliderValue: Float) {
            if (!hasUserSelection) return
            when (selection) {
                Selection.DURATION -> {
                    if (sliderValue >= SLIDER_MAX) {
                        turnOn(DurationMode.INFINITE)
                    } else {
                        turnOnCustomDuration(sliderValue.toInt())
                    }
                }
                Selection.WHILE_CHARGING -> turnOnUntilUnplugged()
                Selection.OFF -> turnOff()
                Selection.NONE -> {}
            }
            refreshState()
        }

        fun onChargingStateChanged() {
            val d = dialog ?: return
            val v = views ?: return
            if (!d.isShowing) return

            mUiHandler.post {
                if (dialog?.isShowing != true) return@post

                val charging = isCharging()
                if (!charging && selection == Selection.WHILE_CHARGING) {
                    selection = Selection.NONE
                    hasUserSelection = false
                    v.unpluggedCard.isChecked = false
                }
                updateCards(v, wakeLock.isHeld)
            }
        }
    }

    private inner class PowerReceiver : BroadcastReceiver() {
        fun register() {
            mContext.registerReceiver(
                this,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_POWER_DISCONNECTED)
                    addAction(Intent.ACTION_POWER_CONNECTED)
                },
                null,
                mHandler,
                Context.RECEIVER_NOT_EXPORTED
            )
        }

        fun unregister() {
            mContext.unregisterReceiver(this)
        }

        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    turnOff()
                    refreshState()
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    if (caffeineState is CaffeineState.UntilUnplugged && wakeLock.isHeld) {
                        turnOff()
                        refreshState()
                    }
                    dialogController.onChargingStateChanged()
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    dialogController.onChargingStateChanged()
                }
            }
        }
    }
}
