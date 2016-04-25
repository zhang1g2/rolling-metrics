/*
 *
 *  Copyright 2016 Vladimir Bukhtoyarov
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package com.github.metricscore.hdrhistogram.accumulator;

import com.codahale.metrics.Clock;
import com.codahale.metrics.Snapshot;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;
import java.util.function.Supplier;

public class ResetByChunksAccumulator implements Accumulator {

    private final long intervalBetweenResettingMillis;
    private final long creationTimestamp;
    private final boolean reportUncompletedChunkToSnapshot;
    private final Chunk[] chunks;
    private final Clock clock;
    private final Histogram temporarySnapshotHistogram;

    private final Phase left;
    private final Phase right;
    private final Phase[] phases;
    private final AtomicReference<Phase> currentPhaseRef;
    private final AtomicInteger activeMutators = new AtomicInteger(0);

    private volatile Runnable postponedPhaseRotation = null;

    public ResetByChunksAccumulator(Supplier<Recorder> recorderSupplier, int numberChunks, long intervalBetweenResettingMillis, boolean reportUncompletedChunkToSnapshot, Clock clock) {
        this.intervalBetweenResettingMillis = intervalBetweenResettingMillis;
        this.clock = clock;
        this.creationTimestamp = clock.getTime();
        this.reportUncompletedChunkToSnapshot = reportUncompletedChunkToSnapshot;

        this.left = new Phase(recorderSupplier, creationTimestamp + intervalBetweenResettingMillis);
        this.right = new Phase(recorderSupplier, Long.MAX_VALUE);
        this.phases = new Phase[] {left, right};
        this.currentPhaseRef = new AtomicReference<>(left);

        this.chunks = new Chunk[numberChunks];
        for (int i = 0; i < numberChunks; i++) {
            Histogram chunkHistogram = left.intervalHistogram.copy();
            this.chunks[i] = new Chunk(chunkHistogram, creationTimestamp + i * numberChunks);
        }
        this.temporarySnapshotHistogram = chunks[0].histogram.copy();
    }

    @Override
    public void recordSingleValueWithExpectedInterval(long value, long expectedIntervalBetweenValueSamples) {
        long currentTimeMillis = clock.getTime();
        Phase currentPhase = currentPhaseRef.get();
        if (currentTimeMillis < currentPhase.proposedInvalidationTimestamp) {
            currentPhase.recorder.recordValueWithExpectedInterval(value, expectedIntervalBetweenValueSamples);
            return;
        }

        Phase nextPhase = currentPhase == left ? right : left;
        if (!currentPhaseRef.compareAndSet(currentPhase, nextPhase)) {
            // another writer achieved progress and must clear current phase data, current writer tread just can write value to next phase and return
            nextPhase.recorder.recordValueWithExpectedInterval(value, expectedIntervalBetweenValueSamples);
            return;
        }

        // Current thread is responsible to rotate phases.
        long millisSinceCreation = currentTimeMillis - creationTimestamp;
        long intervalsSinceCreation = millisSinceCreation / intervalBetweenResettingMillis;
        Runnable phaseRotation = () -> {
            try {
                postponedPhaseRotation = null;

                // move values from recorder to correspondent chunk
                long currentPhaseNumber = (currentPhase.proposedInvalidationTimestamp - creationTimestamp) / intervalBetweenResettingMillis;
                int correspondentChunkIndex = (int) (currentPhaseNumber - 1) % chunks.length;
                currentPhase.intervalHistogram = currentPhase.recorder.getIntervalHistogram(currentPhase.intervalHistogram);
                chunks[correspondentChunkIndex].histogram.add(currentPhase.intervalHistogram);

                currentPhase.proposedInvalidationTimestamp = Long.MAX_VALUE;
                nextPhase.recorder.recordValueWithExpectedInterval(value, expectedIntervalBetweenValueSamples);

                // reset one chunk
                int nextChunkIndex = (int) intervalsSinceCreation % chunks.length;
                chunks[nextChunkIndex].histogram.reset();
                chunks[nextChunkIndex].proposedInvalidationTimestamp = creationTimestamp + (intervalsSinceCreation + chunks.length) * intervalBetweenResettingMillis;
            } finally {
                activeMutators.decrementAndGet();
                nextPhase.proposedInvalidationTimestamp = creationTimestamp + (intervalsSinceCreation + 1) * intervalBetweenResettingMillis;
            }
        };

        // Need to be aware about snapshot takers in the middle of progress state
        if (activeMutators.incrementAndGet() > 1) {
            // give chance to snapshot taker to finalize snapshot extraction, rotation will be complete by snapshot taker thread
            postponedPhaseRotation = phaseRotation;
        } else {
            // There are no active snapshot takers in the progress state, lets exchange phases in this writer thread
            phaseRotation.run();
        }
    }

    @Override
    public final synchronized Snapshot getSnapshot(Function<Histogram, Snapshot> snapshotTaker) {
        long currentTimeMillis = clock.getTime();
        temporarySnapshotHistogram.reset();
        while (!activeMutators.compareAndSet(0, 1)) {
            // if phase rotation process is in progress by writer thread then wait inside spin loop until rotation will done
            LockSupport.parkNanos(TimeUnit.MICROSECONDS.toNanos(500));
        }

        try {


            if (reportUncompletedChunkToSnapshot) {
                for (Phase phase : phases) {
                    if (phase.proposedInvalidationTimestamp > currentTimeMillis) {

                    } else {
                        long currentPhaseNumber = (phase.proposedInvalidationTimestamp - creationTimestamp) / intervalBetweenResettingMillis;
                        int correspondentChunkIndex = (int) (currentPhaseNumber - 1) % chunks.length;
                    }
                }

            }
            for (Chunk chunk : chunks) {
                if (chunk.proposedInvalidationTimestamp > currentTimeMillis) {
                    temporarySnapshotHistogram.add(chunk.histogram);
                }
            }
            Phase currentPhase = currentPhaseRef.get();
            if (currentTimeMillis < currentPhase.proposedInvalidationTimestamp) {
                currentPhase.intervalHistogram = currentPhase.recorder.getIntervalHistogram(currentPhase.intervalHistogram);
                currentPhase.runningTotals.add(currentPhase.intervalHistogram);
                temporarySnapshotHistogram.add(currentPhase.runningTotals);
            }
        } finally {
            if (activeMutators.decrementAndGet() > 0) {
                while (this.postponedPhaseRotation == null) {
                    // wait in spin loop until writer thread provide rotation task
                    LockSupport.parkNanos(TimeUnit.MICROSECONDS.toNanos(100));
                }
                postponedPhaseRotation.run();
            }
        }
        return snapshotTaker.apply(temporarySnapshotHistogram);
    }

    @Override
    public int getEstimatedFootprintInBytes() {
        return temporarySnapshotHistogram.getEstimatedFootprintInBytes() * (chunks.length + 4 + 1);
    }

    private static final class Chunk {

        private final Histogram histogram;
        private volatile long proposedInvalidationTimestamp;

        public Chunk(Histogram histogram, long proposedInvalidationTimestamp) {
            this.histogram = histogram;
            this.proposedInvalidationTimestamp = proposedInvalidationTimestamp;
        }

    }

    private static final class Phase {

        final Recorder recorder;
        Histogram intervalHistogram;
        volatile long proposedInvalidationTimestamp;

        Phase(Supplier<Recorder> recorderSupplier, long proposedInvalidationTimestamp) {
            this.recorder = recorderSupplier.get();
            this.intervalHistogram = recorder.getIntervalHistogram();
            this.proposedInvalidationTimestamp = proposedInvalidationTimestamp;
        }
    }

}
