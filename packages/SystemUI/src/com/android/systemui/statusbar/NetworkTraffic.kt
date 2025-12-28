/**
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

package com.android.systemui.statusbar

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TrafficStats
import android.os.Handler
import android.os.Message
import android.os.SystemClock
import android.text.Spanned
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.AbsoluteSizeSpan
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.DrawableCompat

import android.provider.Settings

import com.android.systemui.Dependency
import com.android.systemui.res.R
import com.android.systemui.plugins.DarkIconDispatcher
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver
import com.android.systemui.tuner.TunerService

import java.text.DecimalFormat

/** @hide */
class NetworkTraffic @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : TextView(context, attrs, defStyle), TunerService.Tunable, DarkReceiver, StatusIconDisplayable {

    private var mMode = MODE_UPSTREAM_AND_DOWNSTREAM
    private var mSubMode = MODE_UPSTREAM_AND_DOWNSTREAM
    private var mIsActive = false
    private var mTrafficActive = false
    private var mTxBytes = 0L
    private var mRxBytes = 0L
    private var mLastTxBytes = 0L
    private var mLastRxBytes = 0L
    private var mLastUpdateTime = 0L
    private var mAutoHide = false
    private var mAutoHideThreshold = 0L
    private var mUnits = 0
    private var mTint = Color.WHITE

    private var mDrawable: Drawable? = null
    private var mRefreshInterval = 2
    private var mAttached = false
    private var mHideArrows = false
    private var mVisible = true
    private var mEnabled = false

    @Volatile
    private var mHasValidatedInternet = false

    private val mLinkPropertiesMap = HashMap<Network, LinkProperties>()
    private var mNetworksChanged = true
    private var mVisibleState = StatusBarIconView.STATE_ICON
    private var mSlot: String? = null
    private var isUsingQs = false
    private var mCurrentWidth = -1

    private val mConnectivityManager: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val mSpeedAbsoluteSizeSpan = AbsoluteSizeSpan(7, true)
    private val mUnitAbsoluteSizeSpan = AbsoluteSizeSpan(6, true)
    private val mDecimalFormat_0_2 = DecimalFormat("0.##")
    private val mDecimalFormat_2_0 = DecimalFormat("##0")
    private val mDecimalFormat_1_1 = DecimalFormat("#0.#")

    private val mTrafficHandler = object : Handler(context.mainLooper) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MESSAGE_TYPE_PERIODIC_REFRESH -> {
                    recalculateStats()
                    displayStatsAndReschedule()
                }
                MESSAGE_TYPE_UPDATE_VIEW -> displayStatsAndReschedule()
                MESSAGE_TYPE_ADD_NETWORK -> {
                    val lph = msg.obj as LinkPropertiesHolder
                    mLinkPropertiesMap[lph.network] = lph.linkProperties
                    mNetworksChanged = true
                    updateViews()
                }
                MESSAGE_TYPE_REMOVE_NETWORK -> {
                    mLinkPropertiesMap.remove(msg.obj as Network)
                    mNetworksChanged = true
                    updateViews()
                }
            }
        }

        private fun recalculateStats() {
            val now = SystemClock.elapsedRealtime()
            val timeDelta = now - mLastUpdateTime

            if (mLastUpdateTime == 0L || timeDelta <= 500) {
                mLastTxBytes = 0
                mLastRxBytes = 0
                mLastUpdateTime = now
                return
            }

            var txBytes = 0L
            var rxBytes = 0L

            val ifaces = mLinkPropertiesMap.values
                .mapNotNull { it.interfaceName }
                .filter { it.isNotEmpty() }
                .distinct()
                .flatMap { listOf(it, CLAT_PREFIX + it) }
                .toTypedArray()

            for (iface in ifaces) {
                txBytes += TrafficStats.getTxBytes(iface)
                rxBytes += TrafficStats.getRxBytes(iface)
            }

            val txBytesDelta = txBytes - mLastTxBytes
            val rxBytesDelta = rxBytes - mLastRxBytes

            if (!mNetworksChanged && timeDelta > 0 && txBytesDelta >= 0 && rxBytesDelta >= 0) {
                mTxBytes = (txBytesDelta / (timeDelta / 1000f)).toLong()
                mRxBytes = (rxBytesDelta / (timeDelta / 1000f)).toLong()
            } else if (mNetworksChanged) {
                mTxBytes = 0
                mRxBytes = 0
                mNetworksChanged = false
            }
            mLastTxBytes = txBytes
            mLastRxBytes = rxBytes
            mLastUpdateTime = now
        }

        private fun displayStatsAndReschedule() {
            val showUpstream = mMode == MODE_UPSTREAM_ONLY || mMode == MODE_UPSTREAM_AND_DOWNSTREAM
            val showDownstream = mMode == MODE_DOWNSTREAM_ONLY || mMode == MODE_UPSTREAM_AND_DOWNSTREAM
            val aboveThreshold = (showUpstream && mTxBytes > mAutoHideThreshold) ||
                (showDownstream && mRxBytes > mAutoHideThreshold)
            mIsActive = mAttached && mHasValidatedInternet && (!mAutoHide || aboveThreshold)

            var submode = MODE_UPSTREAM_AND_DOWNSTREAM
            val trafficactive = mTxBytes > 0 || mRxBytes > 0

            clearHandlerCallbacks()

            if (mEnabled && mIsActive) {
                val output: CharSequence = when {
                    showUpstream && showDownstream -> when {
                        mTxBytes > mRxBytes -> {
                            submode = MODE_UPSTREAM_ONLY
                            formatOutput(mTxBytes)
                        }
                        mTxBytes < mRxBytes -> {
                            submode = MODE_DOWNSTREAM_ONLY
                            formatOutput(mRxBytes)
                        }
                        else -> {
                            submode = MODE_UPSTREAM_AND_DOWNSTREAM
                            formatOutput(mRxBytes)
                        }
                    }
                    showDownstream -> formatOutput(mRxBytes)
                    showUpstream -> formatOutput(mTxBytes)
                    else -> ""
                }

                if (!TextUtils.equals(output, text)) {
                    text = output
                    contentDescription = mContext.getString(
                        R.string.accessibility_network_traffic,
                        output.toString().replace("\n", " ")
                    )
                }
            }

            updateVisibility()

            if (mVisible && (mSubMode != submode || mTrafficActive != trafficactive)) {
                mSubMode = submode
                mTrafficActive = trafficactive
                setTrafficDrawable()
            }

            if (mEnabled && mAttached) {
                sendEmptyMessageDelayed(MESSAGE_TYPE_PERIODIC_REFRESH, mRefreshInterval * 1000L)
            }
        }
    }

    private val mRequest = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .build()

    private val mNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            mTrafficHandler.sendMessage(
                Message.obtain(mTrafficHandler, MESSAGE_TYPE_ADD_NETWORK,
                    LinkPropertiesHolder(network, linkProperties))
            )
        }

        override fun onAvailable(network: Network) {
            val lp = mConnectivityManager.getLinkProperties(network)
            if (lp != null) {
                mTrafficHandler.sendMessage(
                    Message.obtain(mTrafficHandler, MESSAGE_TYPE_ADD_NETWORK,
                        LinkPropertiesHolder(network, lp))
                )
            }
        }

        override fun onLost(network: Network) {
            mTrafficHandler.sendMessage(
                Message.obtain(mTrafficHandler, MESSAGE_TYPE_REMOVE_NETWORK, network)
            )
        }
    }

    private val mDefaultNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            mHasValidatedInternet = hasValidatedInternet(network)
            updateViews()
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            mHasValidatedInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            updateViews()
        }

        override fun onLost(network: Network) {
            mHasValidatedInternet = false
            updateViews()
        }
    }

    private fun formatOutput(speed: Long): CharSequence {
        var adjustedSpeed = speed
        val gunit: String
        val munit: String
        val kunit: String

        if (mUnits == 0) {
            adjustedSpeed *= 8
            gunit = mContext.getString(R.string.gigabitspersecond_short)
            munit = mContext.getString(R.string.megabitspersecond_short)
            kunit = mContext.getString(R.string.kilobitspersecond_short)
        } else {
            gunit = mContext.getString(R.string.gigabytespersecond_short)
            munit = mContext.getString(R.string.megabytespersecond_short)
            kunit = mContext.getString(R.string.kilobytespersecond_short)
        }

        val (unit, formatSpeed) = when {
            adjustedSpeed >= Giga -> gunit to mDecimalFormat_0_2.format(adjustedSpeed / Giga.toFloat())
            adjustedSpeed >= 100 * Mega -> munit to mDecimalFormat_2_0.format(adjustedSpeed / Mega.toFloat())
            adjustedSpeed >= 10 * Mega -> munit to mDecimalFormat_1_1.format(adjustedSpeed / Mega.toFloat())
            adjustedSpeed >= Mega -> munit to mDecimalFormat_0_2.format(adjustedSpeed / Mega.toFloat())
            adjustedSpeed >= 100 * Kilo -> kunit to mDecimalFormat_2_0.format(adjustedSpeed / Kilo.toFloat())
            adjustedSpeed >= 10 * Kilo -> kunit to mDecimalFormat_1_1.format(adjustedSpeed / Kilo.toFloat())
            else -> kunit to mDecimalFormat_0_2.format(adjustedSpeed / Kilo.toFloat())
        }

        val spanSpeedString = SpannableString(formatSpeed).apply {
            setSpan(mSpeedAbsoluteSizeSpan, 0, length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        }
        val spanUnitString = SpannableString(unit).apply {
            setSpan(mUnitAbsoluteSizeSpan, 0, length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        }

        return TextUtils.concat(spanSpeedString, "\n", spanUnitString)
    }

    fun setSlot(slot: String) {
        mSlot = slot
    }

    fun setIsUsingQs(value: Boolean) {
        isUsingQs = value
    }

    override fun setTextColor(color: Int) {
        super.setTextColor(color)
        mTint = color
        mDrawable?.let { DrawableCompat.setTint(it, mTint) }
    }

    override fun onDarkChanged(areas: ArrayList<Rect>, darkIntensity: Float, tint: Int) {
        if (isUsingQs) return
        mTint = DarkIconDispatcher.getTint(areas, this, tint)
        setTextColor(mTint)
    }

    override fun setStaticDrawableColor(color: Int) {}

    override fun setDecorColor(color: Int) {}

    override fun getSlot(): String = mSlot ?: ""

    override fun isIconVisible(): Boolean = mEnabled

    override fun getVisibleState(): Int = mVisibleState

    override fun setVisibleState(state: Int, animate: Boolean) {
        mVisibleState = state
        updateVisibility()
    }

    private fun hasValidatedInternet(network: Network?): Boolean {
        if (network == null) return false
        val caps = mConnectivityManager.getNetworkCapabilities(network)
        return caps != null &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        if (!mAttached) {
            mAttached = true
            val tunerService = Dependency.get(TunerService::class.java)
            tunerService.addTunable(this, NETWORK_TRAFFIC_ENABLED)
            tunerService.addTunable(this, NETWORK_TRAFFIC_MODE)
            tunerService.addTunable(this, NETWORK_TRAFFIC_AUTOHIDE)
            tunerService.addTunable(this, NETWORK_TRAFFIC_AUTOHIDE_THRESHOLD)
            tunerService.addTunable(this, NETWORK_TRAFFIC_UNITS)
            tunerService.addTunable(this, NETWORK_TRAFFIC_REFRESH_INTERVAL)
            tunerService.addTunable(this, NETWORK_TRAFFIC_HIDEARROW)

            mHasValidatedInternet = hasValidatedInternet(mConnectivityManager.activeNetwork)
            mConnectivityManager.registerNetworkCallback(mRequest, mNetworkCallback)
            mConnectivityManager.registerDefaultNetworkCallback(mDefaultNetworkCallback)

            Dependency.get(DarkIconDispatcher::class.java).addDarkReceiver(this)

            updateViews()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (mAttached) {
            clearHandlerCallbacks()
            mConnectivityManager.unregisterNetworkCallback(mDefaultNetworkCallback)
            mConnectivityManager.unregisterNetworkCallback(mNetworkCallback)
            Dependency.get(DarkIconDispatcher::class.java).removeDarkReceiver(this)
            Dependency.get(TunerService::class.java).removeTunable(this)
            mDrawable = null
            setCompoundDrawables(null, null, null, null)
            mAttached = false
        }
    }

    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        super.onRtlPropertiesChanged(layoutDirection)
        if (mAttached && mDrawable != null) {
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, mDrawable, null)
        }
    }

    private fun updateVisibility() {
        val visible = mEnabled && mIsActive &&
            !TextUtils.isEmpty(text) &&
            mVisibleState == StatusBarIconView.STATE_ICON

        if (visible != mVisible) {
            mVisible = visible
            setFixedWidth()
            visibility = if (mVisible) View.VISIBLE else View.GONE
        }
    }

    private fun setFixedWidth() {
        val requiredWidth = when {
            mVisible && !mHideArrows -> resources.getDimensionPixelSize(R.dimen.status_bar_network_traffic_width)
            mVisible -> resources.getDimensionPixelSize(R.dimen.status_bar_network_traffic_width_no_arrows)
            else -> 0
        }
        if (requiredWidth == mCurrentWidth) return
        layoutParams?.let { lp ->
            mCurrentWidth = requiredWidth
            lp.width = requiredWidth
            layoutParams = lp
        }
    }

    override fun onTuningChanged(key: String, newValue: String?) {
        when (key) {
            NETWORK_TRAFFIC_ENABLED -> {
                mEnabled = TunerService.parseIntegerSwitch(newValue, false)
                if (mEnabled) {
                    setLines(2)
                    maxLines = 2
                    val txtFont = resources.getString(com.android.internal.R.string.config_bodyFontFamily)
                    typeface = Typeface.create(txtFont, Typeface.BOLD)
                    setLineSpacing(0f, 0.95f)
                    layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                    textDirection = View.TEXT_DIRECTION_LOCALE
                    textAlignment = View.TEXT_ALIGNMENT_VIEW_END
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    setElegantTextHeight(false)
                    includeFontPadding = false
                    text = formatOutput(0L)
                    setTrafficDrawable()
                    setFixedWidth()
                    mIsActive = true
                } else {
                    clearHandlerCallbacks()
                    mIsActive = false
                    text = ""
                }
                updateVisibility()
                updateViews()
            }
            NETWORK_TRAFFIC_MODE -> {
                mMode = TunerService.parseInteger(newValue, 0)
                updateViews()
                setTrafficDrawable()
            }
            NETWORK_TRAFFIC_AUTOHIDE -> {
                mAutoHide = TunerService.parseIntegerSwitch(newValue, false)
                updateViews()
            }
            NETWORK_TRAFFIC_AUTOHIDE_THRESHOLD -> {
                val threshold = TunerService.parseInteger(newValue, 0)
                mAutoHideThreshold = threshold * Kilo.toLong()
                updateViews()
            }
            NETWORK_TRAFFIC_UNITS -> {
                mUnits = TunerService.parseInteger(newValue, 1)
                updateViews()
            }
            NETWORK_TRAFFIC_REFRESH_INTERVAL -> {
                mRefreshInterval = TunerService.parseInteger(newValue, 2)
                updateViews()
            }
            NETWORK_TRAFFIC_HIDEARROW -> {
                mHideArrows = TunerService.parseIntegerSwitch(newValue, false)
                // Clear pending messages to prevent race conditions
                mTrafficHandler.removeMessages(MESSAGE_TYPE_UPDATE_VIEW)
                mTrafficHandler.removeMessages(MESSAGE_TYPE_PERIODIC_REFRESH)
                setTrafficDrawable()
                setFixedWidth()
                // Reschedule updates if enabled
                if (mEnabled && mAttached) {
                    mTrafficHandler.sendEmptyMessage(MESSAGE_TYPE_UPDATE_VIEW)
                }
            }
        }
    }

    private fun updateViews() {
        if (mEnabled) {
            mTrafficHandler.removeMessages(MESSAGE_TYPE_UPDATE_VIEW)
            mTrafficHandler.sendEmptyMessageDelayed(MESSAGE_TYPE_UPDATE_VIEW, 1000)
        }
    }

    private fun clearHandlerCallbacks() {
        mTrafficHandler.removeCallbacksAndMessages(null)
    }

    private fun setTrafficDrawable() {
        val drawableResId = when {
            mHideArrows -> 0
            !mTrafficActive -> R.drawable.stat_sys_network_traffic
            mMode == MODE_UPSTREAM_ONLY || mSubMode == MODE_UPSTREAM_ONLY ->
                R.drawable.stat_sys_network_traffic_up
            mMode == MODE_DOWNSTREAM_ONLY || mSubMode == MODE_DOWNSTREAM_ONLY ->
                R.drawable.stat_sys_network_traffic_down
            mMode == MODE_UPSTREAM_AND_DOWNSTREAM -> R.drawable.stat_sys_network_traffic_updown
            else -> 0
        }

        val drawable = if (mHideArrows || drawableResId == 0) null
        else ResourcesCompat.getDrawable(resources, drawableResId, context.theme)

        drawable?.let { DrawableCompat.setTint(it, mTint) }

        if (mHideArrows || mDrawable != drawable) {
            mDrawable = drawable
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, mDrawable, null)
        }
    }

    private data class LinkPropertiesHolder(
        val network: Network,
        val linkProperties: LinkProperties
    )

    companion object {
        private const val TAG = "NetworkTraffic"
        private const val CLAT_PREFIX = "v4-"

        private const val MODE_UPSTREAM_AND_DOWNSTREAM = 0
        private const val MODE_UPSTREAM_ONLY = 1
        private const val MODE_DOWNSTREAM_ONLY = 2

        private const val MESSAGE_TYPE_PERIODIC_REFRESH = 0
        private const val MESSAGE_TYPE_UPDATE_VIEW = 1
        private const val MESSAGE_TYPE_ADD_NETWORK = 2
        private const val MESSAGE_TYPE_REMOVE_NETWORK = 3

        private const val Kilo = 1000
        private const val Mega = Kilo * Kilo
        private const val Giga = Mega * Kilo

        private const val NETWORK_TRAFFIC_ENABLED =
            "system:" + Settings.System.NETWORK_TRAFFIC_ENABLED
        private const val NETWORK_TRAFFIC_MODE =
            "system:" + Settings.System.NETWORK_TRAFFIC_MODE
        private const val NETWORK_TRAFFIC_AUTOHIDE =
            "system:" + Settings.System.NETWORK_TRAFFIC_AUTOHIDE
        private const val NETWORK_TRAFFIC_AUTOHIDE_THRESHOLD =
            "system:" + Settings.System.NETWORK_TRAFFIC_AUTOHIDE_THRESHOLD
        private const val NETWORK_TRAFFIC_UNITS =
            "system:" + Settings.System.NETWORK_TRAFFIC_UNITS
        private const val NETWORK_TRAFFIC_REFRESH_INTERVAL =
            "system:" + Settings.System.NETWORK_TRAFFIC_REFRESH_INTERVAL
        private const val NETWORK_TRAFFIC_HIDEARROW =
            "system:" + Settings.System.NETWORK_TRAFFIC_HIDEARROW

        @JvmStatic
        fun fromContext(context: Context, slot: String): NetworkTraffic {
            return NetworkTraffic(context).apply { setSlot(slot) }
        }
    }
}
