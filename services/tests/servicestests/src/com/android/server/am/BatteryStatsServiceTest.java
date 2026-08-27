/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.am;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.BatteryStatsInternal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PermissionEnforcer;
import android.os.Process;
import android.os.UserHandle;
import android.util.SparseArray;
import android.util.SparseIntArray;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.app.ICpuWakeupCallback;
import com.android.internal.app.WakeAttributionEvent;
import com.android.internal.app.WakeAttributionEventBatch;
import com.android.server.power.stats.BatteryStatsImpl;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(AndroidJUnit4.class)
public final class BatteryStatsServiceTest {

    private BatteryStatsService mBatteryStatsService;
    private HandlerThread mBgThread;
    private PermissionEnforcer mPermissionEnforcer;

    @Before
    public void setUp() {
        final Context context = InstrumentationRegistry.getContext();
        mBgThread = new HandlerThread("bg thread");
        mBgThread.start();
        File systemDir = context.getCacheDir();
        Handler handler = new Handler(mBgThread.getLooper());
        mPermissionEnforcer = Mockito.mock(PermissionEnforcer.class);
        mBatteryStatsService =
                new BatteryStatsService(context, systemDir, mPermissionEnforcer);
        mBatteryStatsService.setRailsStatsCollectionEnabled(false);
        mBatteryStatsService.systemServicesReady();
    }

    @After
    public void tearDown() {
        mBatteryStatsService.shutdown();
        mBgThread.quitSafely();
    }

    @Test
    @Ignore("b/180015146")
    public void testAwaitCompletion() throws Exception {
        final CountDownLatch readyLatch = new CountDownLatch(2);
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch testLatch = new CountDownLatch(1);
        final AtomicBoolean quiting = new AtomicBoolean(false);
        final AtomicBoolean finished = new AtomicBoolean(false);
        final int uid = Process.myUid();
        final Thread noteThread = new Thread(() -> {
            final int maxIterations = 1000;
            final int eventCode = 12345;
            final String eventName = "placeholder";
            final BatteryStatsImpl stats = mBatteryStatsService.getActiveStatistics();

            readyLatch.countDown();
            try {
                startLatch.await();
            } catch (InterruptedException e) {
            }

            for (int i = 0; i < maxIterations && !quiting.get(); i++) {
                synchronized (stats) {
                    mBatteryStatsService.noteEvent(eventCode, eventName, uid);
                }
            }
            finished.set(true);
        });
        final Thread waitThread = new Thread(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
            } catch (InterruptedException e) {
            }

            do {
                mBatteryStatsService.takeUidSnapshot(uid);
            } while (!finished.get() && !quiting.get());

            if (!quiting.get()) {
                // do one more to ensure we've cleared the queue
                mBatteryStatsService.takeUidSnapshot(uid);
            }

            testLatch.countDown();
        });
        noteThread.start();
        waitThread.start();
        readyLatch.await();
        startLatch.countDown();

        try {
            assertTrue("Timed out in waiting for the completion of battery event handling",
                    testLatch.await(10 * 1000, TimeUnit.MILLISECONDS));
        } finally {
            quiting.set(true);
            noteThread.interrupt();
            noteThread.join(1000);
            waitThread.interrupt();
            waitThread.join(1000);
        }
    }

    @Test
    public void testSavingStatsdAtomPullTimestamp() {
        mBatteryStatsService.setLastBatteryUsageStatsBeforeResetAtomPullTimestamp(1234);
        assertThat(mBatteryStatsService.getLastBatteryUsageStatsBeforeResetAtomPullTimestamp())
                .isEqualTo(1234);
        mBatteryStatsService.setLastBatteryUsageStatsBeforeResetAtomPullTimestamp(5478);
        assertThat(mBatteryStatsService.getLastBatteryUsageStatsBeforeResetAtomPullTimestamp())
                .isEqualTo(5478);
    }

    @Test
    public void wakeAttributionRequiresRequestedSession() {
        mBatteryStatsService.noteWakeAttributionEvent(1L, 1L, "alarm",
                attributionWithUids(Process.myUid()));

        assertThat(mBatteryStatsService.getPendingWakeAttributionEventCount()).isEqualTo(0);
        assertThrows(SecurityException.class,
                () -> mBatteryStatsService.drainWakeAttributionEvents(1));
    }

    @Test
    public void wakeAttributionDrainRequiresPermission() {
        Mockito.doThrow(new SecurityException()).when(mPermissionEnforcer).enforcePermission(
                Mockito.eq(android.Manifest.permission.OBSERVE_TRACING_SESSION),
                Mockito.anyInt(), Mockito.anyInt());

        assertThrows(SecurityException.class,
                () -> mBatteryStatsService.drainWakeAttributionEvents(1));
    }

    @Test
    public void wakeAttributionQueueDropsOldestAndReportsCount() {
        registerWakeAttributionCollector();
        for (int i = 0; i <= 4096; i++) {
            mBatteryStatsService.noteWakeAttributionEvent(i, i, "alarm",
                    attributionWithUids(Process.myUid()));
        }

        final WakeAttributionEventBatch batch =
                mBatteryStatsService.drainWakeAttributionEvents(512);

        assertThat(batch.events).hasSize(512);
        assertThat(batch.events.get(0).elapsedRealtimeMillis).isEqualTo(1L);
        assertThat(batch.droppedCount).isEqualTo(1L);
    }

    @Test
    public void wakeAttributionStripsForeignUidsAndMarksIncomplete() {
        registerWakeAttributionCollector();
        final int appId = Math.max(Process.FIRST_APPLICATION_UID,
                UserHandle.getAppId(Process.myUid()));
        final int ownUid = UserHandle.getUid(UserHandle.myUserId(), appId);
        final int foreignUid = UserHandle.getUid(UserHandle.myUserId() + 1, appId);
        mBatteryStatsService.noteWakeAttributionEvent(1L, 1L, "alarm",
                attributionWithUids(ownUid, foreignUid));

        final WakeAttributionEvent event =
                mBatteryStatsService.drainWakeAttributionEvents(1).events.get(0);

        assertThat(event.candidateUids[0]).asList().containsExactly(ownUid);
        assertThat(event.incomplete).isTrue();
    }

    @Test
    public void wakeAttributionRecordIsFrozenWhenQueued() {
        registerWakeAttributionCollector();
        final SparseArray<SparseIntArray> attribution = attributionWithUids(Process.myUid());
        mBatteryStatsService.noteWakeAttributionEvent(1L, 1L, "alarm", attribution);
        attribution.valueAt(0).put(Process.myUid() + 1, 0);

        final WakeAttributionEvent event =
                mBatteryStatsService.drainWakeAttributionEvents(1).events.get(0);

        assertThat(event.candidateUids[0]).asList().containsExactly(Process.myUid());
    }

    @Test
    public void wakeAttributionCapsCandidatesSoADrainCannotOutgrowABinderTransaction() {
        registerWakeAttributionCollector();
        final SparseIntArray uidProcStates = new SparseIntArray();
        for (int i = 0; i < 500; i++) {
            uidProcStates.put(Process.FIRST_APPLICATION_UID + i, 0);
        }
        final SparseArray<SparseIntArray> attribution = new SparseArray<>();
        attribution.put(0, uidProcStates);
        mBatteryStatsService.noteWakeAttributionEvent(1L, 1L, "alarm", attribution);

        final WakeAttributionEvent event =
                mBatteryStatsService.drainWakeAttributionEvents(1).events.get(0);

        assertThat(event.candidateUids[0].length).isAtMost(32);
        assertThat(event.incomplete).isTrue();
    }

    @Test
    public void wakeAttributionWithOnlyForeignUidsIsUnattributedAndIncomplete() {
        registerWakeAttributionCollector();
        final int appId = Math.max(Process.FIRST_APPLICATION_UID,
                UserHandle.getAppId(Process.myUid()));
        final int foreignUid = UserHandle.getUid(UserHandle.myUserId() + 1, appId);
        mBatteryStatsService.noteWakeAttributionEvent(1L, 1L, "alarm",
                attributionWithUids(foreignUid));

        final WakeAttributionEvent event =
                mBatteryStatsService.drainWakeAttributionEvents(1).events.get(0);

        assertThat(event.candidateUids[0]).isEmpty();
        assertThat(event.incomplete).isTrue();
    }

    private void registerWakeAttributionCollector() {
        mBatteryStatsService.registerCpuWakeupCallback(new ICpuWakeupCallback.Stub() {
            @Override
            public void onCpuWakeup(long elapsedRealtimeMillis, long uptimeMillis, String reason) {
            }

            @Override
            public void onCpuTimeBucketsPressure() {
            }
        }, false, false, false, true, false);
    }

    private static SparseArray<SparseIntArray> attributionWithUids(int... uids) {
        final SparseIntArray uidProcStates = new SparseIntArray();
        for (int uid : uids) {
            uidProcStates.put(uid, 0);
        }
        final SparseArray<SparseIntArray> attribution = new SparseArray<>();
        attribution.put(BatteryStatsInternal.CPU_WAKEUP_SUBSYSTEM_ALARM, uidProcStates);
        return attribution;
    }
}
