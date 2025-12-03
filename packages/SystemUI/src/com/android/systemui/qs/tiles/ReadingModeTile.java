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

package com.android.systemui.qs.tiles;

import static com.android.internal.logging.MetricsLogger.VIEW_UNKNOWN;

import android.content.Intent;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.view.accessibility.AccessibilityManager;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.animation.Expandable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;

import javax.inject.Inject;

/** Quick settings tile: Reading Mode (Grayscale) **/
public class ReadingModeTile extends QSTileImpl<BooleanState> {

    public static final String TILE_SPEC = "reading_mode";

    private static final Intent DISPLAY_SETTINGS =
            new Intent(Settings.ACTION_DISPLAY_SETTINGS);

    // Daltonizer mode for grayscale (monochromacy simulation)
    private static final int DALTONIZER_GRAYSCALE = AccessibilityManager.DALTONIZER_SIMULATE_MONOCHROMACY;

    @Nullable
    private Icon mIcon = null;

    private boolean mListening;
    private final ContentObserver mObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            refreshState();
        }
    };

    @Inject
    public ReadingModeTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleSetListening(boolean listening) {
        if (mListening == listening) {
            return;
        }
        mListening = listening;
        if (listening) {
            mContext.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED),
                    false, mObserver);
            mContext.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER),
                    false, mObserver);
        } else {
            mContext.getContentResolver().unregisterContentObserver(mObserver);
        }
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        boolean newState = !isReadingModeEnabled();
        setReadingModeEnabled(newState);
        refreshState();
    }

    @Override
    public Intent getLongClickIntent() {
        return DISPLAY_SETTINGS;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.value = isReadingModeEnabled();
        if (mIcon == null) {
            mIcon = maybeLoadResourceIcon(R.drawable.ic_qs_reading_mode);
        }
        state.icon = mIcon;
        state.label = getTileLabel();
        if (state.value) {
            state.contentDescription = mContext.getString(
                    R.string.accessibility_quick_settings_reading_mode_on);
            state.state = Tile.STATE_ACTIVE;
        } else {
            state.contentDescription = mContext.getString(
                    R.string.accessibility_quick_settings_reading_mode_off);
            state.state = Tile.STATE_INACTIVE;
        }
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_reading_mode);
    }

    @Override
    public int getMetricsCategory() {
        return VIEW_UNKNOWN;
    }

    private boolean isReadingModeEnabled() {
        int daltonizerEnabled = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED, 0);
        int daltonizerMode = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER, -1);
        return daltonizerEnabled == 1 && daltonizerMode == DALTONIZER_GRAYSCALE;
    }

    private void setReadingModeEnabled(boolean enabled) {
        if (enabled) {
            // Enable daltonizer with grayscale mode
            Settings.Secure.putInt(mContext.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER, DALTONIZER_GRAYSCALE);
            Settings.Secure.putInt(mContext.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED, 1);
        } else {
            // Disable daltonizer
            Settings.Secure.putInt(mContext.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED, 0);
        }
    }
}
