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

import android.util.SparseArray;

import java.util.ArrayList;

/**
 * Which app owned an isolated uid at a given moment.
 *
 * <p>{@link PowerStatsUidResolver} answers for the present, which is enough for collectors that
 * attribute work as it happens. A collector that queues work and hands it over later needs the
 * past instead, and gets it wrong in two directions without this. The resolver drops a mapping
 * five minutes after the process dies, and Android reuses isolated uids, so a later process would
 * be charged for work the previous one did.
 */
public class UidOwnershipHistory {
    private final SparseArray<ArrayList<Ownership>> mOwnershipsByUid = new SparseArray<>();
    private final long mSessionStartElapsedRealtimeMs;
    private final int mMaxOwnerships;
    private int mOwnershipCount;

    private static final class Ownership {
        final int ownerUid;
        final long startElapsedRealtimeMs;
        long endElapsedRealtimeMs = Long.MAX_VALUE;

        Ownership(int ownerUid, long startElapsedRealtimeMs) {
            this.ownerUid = ownerUid;
            this.startElapsedRealtimeMs = startElapsedRealtimeMs;
        }
    }

    public UidOwnershipHistory(long sessionStartElapsedRealtimeMs, int maxOwnerships) {
        mSessionStartElapsedRealtimeMs = sessionStartElapsedRealtimeMs;
        mMaxOwnerships = maxOwnerships;
    }

    /**
     * When the session owning this history began. A process older than the session is only ever
     * named as it exits, and gets a claim from here, which the session's own start time must
     * supply because a restart would otherwise hand a stale drain the new session's clock.
     */
    public long sessionStartElapsedRealtimeMs() {
        return mSessionStartElapsedRealtimeMs;
    }

    /** Records that [ownerUid] took [uid] at [elapsedRealtimeMs]. */
    public void onUidTaken(int uid, int ownerUid, long elapsedRealtimeMs) {
        ArrayList<Ownership> ownerships = mOwnershipsByUid.get(uid);
        if (ownerships == null) {
            ownerships = new ArrayList<>(1);
            mOwnershipsByUid.put(uid, ownerships);
        } else if (!ownerships.isEmpty()) {
            final Ownership last = ownerships.get(ownerships.size() - 1);
            if (last.endElapsedRealtimeMs == Long.MAX_VALUE) {
                if (last.ownerUid == ownerUid) return;
                // A release that never arrived, so the previous claim ends where this one begins.
                last.endElapsedRealtimeMs = elapsedRealtimeMs;
            }
        }
        ownerships.add(new Ownership(ownerUid, elapsedRealtimeMs));
        mOwnershipCount++;
        trim();
    }

    /** Records that whoever held [uid] released it at [elapsedRealtimeMs]. */
    public void onUidReleased(int uid, long elapsedRealtimeMs) {
        final ArrayList<Ownership> ownerships = mOwnershipsByUid.get(uid);
        if (ownerships == null || ownerships.isEmpty()) return;
        final Ownership last = ownerships.get(ownerships.size() - 1);
        if (last.endElapsedRealtimeMs == Long.MAX_VALUE) {
            last.endElapsedRealtimeMs = elapsedRealtimeMs;
        }
    }

    /**
     * The uid that owned [uid] over [startElapsedRealtimeMs, endElapsedRealtimeMs), or
     * [defaultUid] if nothing is recorded from then.
     *
     * <p>Callers hold intervals, not instants. A collector bucket is a fixed slot on a grid, so a
     * process created part way through one still earns work filed under the slot's start, before
     * its own claim began. The claim covering most of the interval wins. Two owners inside one
     * slot cannot be separated, because the value the kernel reports has already combined them.
     *
     * <p>A claim past its end still answers when nothing overlaps, since the uid stays with that
     * process until another takes it.
     */
    public int ownerAt(int uid, long startElapsedRealtimeMs, long endElapsedRealtimeMs,
            int defaultUid) {
        final ArrayList<Ownership> ownerships = mOwnershipsByUid.get(uid);
        if (ownerships == null) return defaultUid;
        int bestOwnerUid = defaultUid;
        long bestOverlap = 0L;
        int precedingOwnerUid = defaultUid;
        boolean hasPreceding = false;
        for (int i = ownerships.size() - 1; i >= 0; i--) {
            final Ownership ownership = ownerships.get(i);
            if (ownership.startElapsedRealtimeMs >= endElapsedRealtimeMs) continue;
            if (!hasPreceding) {
                hasPreceding = true;
                precedingOwnerUid = ownership.ownerUid;
            }
            final long overlapStart =
                    Math.max(startElapsedRealtimeMs, ownership.startElapsedRealtimeMs);
            final long overlapEnd = Math.min(endElapsedRealtimeMs, ownership.endElapsedRealtimeMs);
            if (overlapEnd - overlapStart > bestOverlap) {
                bestOverlap = overlapEnd - overlapStart;
                bestOwnerUid = ownership.ownerUid;
            }
            if (ownership.endElapsedRealtimeMs <= startElapsedRealtimeMs) break;
        }
        return bestOverlap > 0L ? bestOwnerUid : precedingOwnerUid;
    }

    /** True when [uid] has no recorded history, so a caller may seed one. */
    public boolean isUnknown(int uid) {
        final ArrayList<Ownership> ownerships = mOwnershipsByUid.get(uid);
        return ownerships == null || ownerships.isEmpty();
    }

    /**
     * Drops claims released before [elapsedRealtimeMs], which the caller must set to the oldest
     * moment any undelivered record can still refer to. The size bound alone cannot do this job,
     * since a burst of short-lived processes evicts claims whose work has not been collected yet.
     */
    public void pruneReleasedBefore(long elapsedRealtimeMs) {
        for (int i = mOwnershipsByUid.size() - 1; i >= 0; i--) {
            final ArrayList<Ownership> ownerships = mOwnershipsByUid.valueAt(i);
            while (!ownerships.isEmpty()
                    && ownerships.get(0).endElapsedRealtimeMs < elapsedRealtimeMs) {
                ownerships.remove(0);
                mOwnershipCount--;
            }
            if (ownerships.isEmpty()) mOwnershipsByUid.removeAt(i);
        }
    }

    /** Drops the longest-released claim first, so a fork loop cannot grow this without bound. */
    private void trim() {
        while (mOwnershipCount > mMaxOwnerships) {
            int victimUidIndex = -1;
            int victimIndex = -1;
            long oldestEnd = Long.MAX_VALUE;
            for (int i = mOwnershipsByUid.size() - 1; i >= 0; i--) {
                final ArrayList<Ownership> ownerships = mOwnershipsByUid.valueAt(i);
                for (int j = 0; j < ownerships.size(); j++) {
                    final long end = ownerships.get(j).endElapsedRealtimeMs;
                    if (end < oldestEnd) {
                        oldestEnd = end;
                        victimUidIndex = i;
                        victimIndex = j;
                    }
                }
            }
            // Every claim is still open, so there is nothing safe to drop.
            if (victimUidIndex < 0) return;
            final ArrayList<Ownership> ownerships = mOwnershipsByUid.valueAt(victimUidIndex);
            ownerships.remove(victimIndex);
            if (ownerships.isEmpty()) mOwnershipsByUid.removeAt(victimUidIndex);
            mOwnershipCount--;
        }
    }
}
