/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.server.power.stats;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class UidOwnershipHistoryTest {
    private static final int ISOLATED_UID = 99001;
    private static final int FIRST_OWNER = 10123;
    private static final int SECOND_OWNER = 10456;
    private static final long BUCKET_MS = 500L;

    private final UidOwnershipHistory mHistory = new UidOwnershipHistory(0L, 16);

    private int ownerOfBucketAt(long startMs) {
        return mHistory.ownerAt(ISOLATED_UID, startMs, startMs + BUCKET_MS, ISOLATED_UID);
    }

    @Test
    public void reusedUidDoesNotChargeTheNewOwnerForOldWork() {
        mHistory.onUidTaken(ISOLATED_UID, FIRST_OWNER, 1_000L);
        mHistory.onUidReleased(ISOLATED_UID, 2_000L);
        mHistory.onUidTaken(ISOLATED_UID, SECOND_OWNER, 5_000L);

        assertThat(ownerOfBucketAt(1_500L)).isEqualTo(FIRST_OWNER);
        assertThat(ownerOfBucketAt(5_500L)).isEqualTo(SECOND_OWNER);
    }

    @Test
    public void ownershipOutlivesTheProcessSoALateDrainStillAttributes() {
        mHistory.onUidTaken(ISOLATED_UID, FIRST_OWNER, 1_000L);
        mHistory.onUidReleased(ISOLATED_UID, 2_000L);

        // Work inside the process's life, drained long after the platform resolver forgot it.
        assertThat(ownerOfBucketAt(1_500L)).isEqualTo(FIRST_OWNER);
    }

    @Test
    public void aProcessStartedInsideABucketStillOwnsThatBucket() {
        // The kernel files work under the slot start, which precedes the process that did it.
        mHistory.onUidTaken(ISOLATED_UID, FIRST_OWNER, 1_250L);

        assertThat(ownerOfBucketAt(1_000L)).isEqualTo(FIRST_OWNER);
    }

    @Test
    public void aBucketSharedByTwoOwnersGoesToWhicheverHeldItLonger() {
        mHistory.onUidTaken(ISOLATED_UID, FIRST_OWNER, 900L);
        mHistory.onUidReleased(ISOLATED_UID, 1_400L);
        mHistory.onUidTaken(ISOLATED_UID, SECOND_OWNER, 1_400L);

        // The kernel already combined both, so the majority holder is the closest answer there is.
        assertThat(ownerOfBucketAt(1_000L)).isEqualTo(FIRST_OWNER);
        assertThat(ownerOfBucketAt(1_500L)).isEqualTo(SECOND_OWNER);
    }

    @Test
    public void workBeforeAnyRecordedClaimFallsBackToTheRawUid() {
        mHistory.onUidTaken(ISOLATED_UID, FIRST_OWNER, 5_000L);

        assertThat(ownerOfBucketAt(500L)).isEqualTo(ISOLATED_UID);
        assertThat(mHistory.ownerAt(ISOLATED_UID + 1, 1_500L, 2_000L, ISOLATED_UID + 1))
                .isEqualTo(ISOLATED_UID + 1);
    }

    @Test
    public void aClaimWhoseReleaseNeverArrivedEndsWhereTheNextBegins() {
        mHistory.onUidTaken(ISOLATED_UID, FIRST_OWNER, 1_000L);
        mHistory.onUidTaken(ISOLATED_UID, SECOND_OWNER, 3_000L);

        assertThat(ownerOfBucketAt(2_000L)).isEqualTo(FIRST_OWNER);
        assertThat(ownerOfBucketAt(3_000L)).isEqualTo(SECOND_OWNER);
    }

    @Test
    public void repeatedClaimsByTheSameOwnerDoNotAccumulate() {
        final UidOwnershipHistory history = new UidOwnershipHistory(0L, 1);
        history.onUidTaken(ISOLATED_UID, FIRST_OWNER, 1_000L);
        history.onUidTaken(ISOLATED_UID, FIRST_OWNER, 2_000L);

        assertThat(history.ownerAt(ISOLATED_UID, 1_500L, 2_000L, ISOLATED_UID))
                .isEqualTo(FIRST_OWNER);
    }

    @Test
    public void pruningKeepsClaimsWhoseBucketsCouldStillBePending() {
        mHistory.onUidTaken(ISOLATED_UID, FIRST_OWNER, 1_000L);
        mHistory.onUidReleased(ISOLATED_UID, 2_000L);
        mHistory.onUidTaken(ISOLATED_UID + 1, SECOND_OWNER, 3_000L);
        mHistory.onUidReleased(ISOLATED_UID + 1, 4_000L);

        mHistory.pruneReleasedBefore(3_000L);

        assertThat(mHistory.isUnknown(ISOLATED_UID)).isTrue();
        assertThat(mHistory.ownerAt(ISOLATED_UID + 1, 3_500L, 4_000L, 0)).isEqualTo(SECOND_OWNER);
    }

    @Test
    public void pruningNeverDropsALiveClaim() {
        mHistory.onUidTaken(ISOLATED_UID, FIRST_OWNER, 1_000L);

        mHistory.pruneReleasedBefore(Long.MAX_VALUE);

        assertThat(ownerOfBucketAt(1_000L)).isEqualTo(FIRST_OWNER);
    }

    @Test
    public void aForkLoopCannotGrowTheHistoryWithoutBound() {
        final UidOwnershipHistory history = new UidOwnershipHistory(0L, 4);
        for (int i = 0; i < 1000; i++) {
            history.onUidTaken(99000 + i, FIRST_OWNER, i);
            history.onUidReleased(99000 + i, i + 1);
        }

        // The newest claim survives, which is the one a pending drain is most likely to need.
        assertThat(history.ownerAt(99999, 999L, 1_000L, 99999)).isEqualTo(FIRST_OWNER);
    }

    @Test
    public void eachSessionCarriesItsOwnStartAndItsOwnClaims() {
        final UidOwnershipHistory first = new UidOwnershipHistory(1_000L, 16);
        first.onUidTaken(ISOLATED_UID, FIRST_OWNER, 1_200L);

        // A restart takes a new history, so a drain holding the old one is unaffected by it.
        final UidOwnershipHistory second = new UidOwnershipHistory(9_000L, 16);
        second.onUidTaken(ISOLATED_UID, SECOND_OWNER, 9_100L);

        assertThat(first.sessionStartElapsedRealtimeMs()).isEqualTo(1_000L);
        assertThat(second.sessionStartElapsedRealtimeMs()).isEqualTo(9_000L);
        assertThat(first.ownerAt(ISOLATED_UID, 1_000L, 1_500L, ISOLATED_UID))
                .isEqualTo(FIRST_OWNER);
        assertThat(second.ownerAt(ISOLATED_UID, 1_000L, 1_500L, ISOLATED_UID))
                .isEqualTo(ISOLATED_UID);
    }
}
