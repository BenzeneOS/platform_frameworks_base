package com.android.systemui.statusbar.phone;

import static com.android.systemui.doze.util.BurnInHelperKt.getBurnInOffset;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.android.systemui.doze.DozeReceiver;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.res.R;

import java.util.function.Consumer;

public final class NowPlayingIndicationView extends TextView
        implements DozeReceiver {
    private static final String ACTION_HIDE =
            "com.google.android.ambientindication.action.AMBIENT_INDICATION_HIDE";
    private static final String ACTION_SHOW =
            "com.google.android.ambientindication.action.AMBIENT_INDICATION_SHOW";
    private static final String EXTRA_OPEN_INTENT =
            "com.google.android.ambientindication.extra.OPEN_INTENT";
    private static final String EXTRA_TEXT =
            "com.google.android.ambientindication.extra.TEXT";
    private static final String EXTRA_TTL_MILLIS =
            "com.google.android.ambientindication.extra.TTL_MILLIS";
    private static final String EXTRA_VERSION =
            "com.google.android.ambientindication.extra.VERSION";
    private static final long MAX_TTL_MILLIS = 300_000L;
    private static final int VERSION = 1;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getIntExtra(EXTRA_VERSION, 0) != VERSION) {
                return;
            }
            if (ACTION_SHOW.equals(intent.getAction())) {
                show(intent);
            } else if (ACTION_HIDE.equals(intent.getAction())) {
                hide();
            }
        }
    };
    private final StatusBarStateController.StateListener mStatusBarStateListener =
            new StatusBarStateController.StateListener() {
                @Override
                public void onDozingChanged(boolean isDozing) {
                    mDozing = isDozing;
                    if (isDozing) {
                        mHandler.removeCallbacks(mHide);
                        updateBurnInOffsets();
                    } else {
                        setTranslationX(0);
                        setTranslationY(0);
                        scheduleHide();
                    }
                }
            };
    private final Runnable mHide = this::hide;

    @Nullable private ActivityStarter mActivityStarter;
    @Nullable private Consumer<Boolean> mVisibilityListener;
    @Nullable private PendingIntent mOpenIntent;
    @Nullable private StatusBarStateController mStatusBarStateController;
    private long mExpiresAtElapsedRealtime;
    private boolean mDozing;
    private boolean mReceiverRegistered;
    private boolean mStateListenerRegistered;

    public NowPlayingIndicationView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOnClickListener(this::openHistory);
    }

    public void initialize(
            ActivityStarter activityStarter,
            Consumer<Boolean> visibilityListener,
            StatusBarStateController statusBarStateController) {
        mActivityStarter = activityStarter;
        mVisibilityListener = visibilityListener;
        mStatusBarStateController = statusBarStateController;
        if (isAttachedToWindow()) {
            registerStateListener();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!mReceiverRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_SHOW);
            filter.addAction(ACTION_HIDE);
            getContext().registerReceiver(
                    mReceiver,
                    filter,
                    Manifest.permission.MANAGE_SOUND_TRIGGER,
                    mHandler,
                    Context.RECEIVER_EXPORTED);
            mReceiverRegistered = true;
        }
        registerStateListener();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mReceiverRegistered) {
            getContext().unregisterReceiver(mReceiver);
            mReceiverRegistered = false;
        }
        if (mStateListenerRegistered && mStatusBarStateController != null) {
            mStatusBarStateController.removeCallback(mStatusBarStateListener);
            mStateListenerRegistered = false;
        }
        mHandler.removeCallbacks(mHide);
        setAmbientIndicationVisible(false);
        super.onDetachedFromWindow();
    }

    @Override
    public void dozeTimeTick() {
        if (hasExpired()) {
            hide();
        } else {
            updateBurnInOffsets();
        }
    }

    private void registerStateListener() {
        if (!mStateListenerRegistered && mStatusBarStateController != null) {
            mStatusBarStateController.addCallback(mStatusBarStateListener);
            mStateListenerRegistered = true;
            mStatusBarStateListener.onDozingChanged(mStatusBarStateController.isDozing());
        }
    }

    private void show(Intent intent) {
        CharSequence text = intent.getCharSequenceExtra(EXTRA_TEXT);
        long ttlMillis = intent.getLongExtra(EXTRA_TTL_MILLIS, 0L);
        PendingIntent openIntent = intent.getParcelableExtra(EXTRA_OPEN_INTENT, PendingIntent.class);
        if (TextUtils.isEmpty(text) || ttlMillis <= 0L || openIntent == null) {
            hide();
            return;
        }
        mOpenIntent = openIntent;
        mExpiresAtElapsedRealtime = SystemClock.elapsedRealtime()
                + Math.min(ttlMillis, MAX_TTL_MILLIS);
        setText(text);
        setVisibility(View.VISIBLE);
        setAmbientIndicationVisible(true);
        if (mDozing) {
            updateBurnInOffsets();
        } else {
            scheduleHide();
        }
    }

    private void hide() {
        mHandler.removeCallbacks(mHide);
        mExpiresAtElapsedRealtime = 0L;
        mOpenIntent = null;
        setText(null);
        setVisibility(View.GONE);
        setAmbientIndicationVisible(false);
    }

    private void scheduleHide() {
        mHandler.removeCallbacks(mHide);
        if (mExpiresAtElapsedRealtime == 0L) {
            return;
        }
        long remainingMillis = mExpiresAtElapsedRealtime - SystemClock.elapsedRealtime();
        if (remainingMillis <= 0L) {
            hide();
        } else if (!mDozing) {
            mHandler.postDelayed(mHide, remainingMillis);
        }
    }

    private boolean hasExpired() {
        return mExpiresAtElapsedRealtime != 0L
                && SystemClock.elapsedRealtime() >= mExpiresAtElapsedRealtime;
    }

    private void updateBurnInOffsets() {
        if (!mDozing || getVisibility() != View.VISIBLE) {
            return;
        }
        int maxX = getResources().getDimensionPixelSize(R.dimen.burn_in_prevention_offset_x);
        int maxY = getResources().getDimensionPixelSize(
                R.dimen.default_burn_in_prevention_offset);
        setTranslationX(getBurnInOffset(maxX * 2, true) - maxX);
        setTranslationY(getBurnInOffset(maxY * 2, false) - maxY);
    }

    private void openHistory(View view) {
        if (hasExpired()) {
            hide();
            return;
        }
        if (mActivityStarter != null && mOpenIntent != null) {
            mActivityStarter.postStartActivityDismissingKeyguard(mOpenIntent);
        }
    }

    private void setAmbientIndicationVisible(boolean visible) {
        if (mVisibilityListener != null) {
            mVisibilityListener.accept(visible);
        }
    }
}
