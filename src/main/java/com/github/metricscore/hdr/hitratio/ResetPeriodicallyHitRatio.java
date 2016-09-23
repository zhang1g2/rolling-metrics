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

package com.github.metricscore.hdr.hitratio;

import com.codahale.metrics.Clock;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

public class ResetPeriodicallyHitRatio implements HitRatio {

    private final AtomicLong ratio = new AtomicLong();
    private final long resetIntervalMillis;
    private final Clock clock;
    private final AtomicLong nextResetTimeMillisRef;

    /**
     * Constructs the HitRatio which reset its state to zero each time when {@code resetInterval} is elapsed.
     *
     * @param resetInterval the interval between counter resetting
     */
    public ResetPeriodicallyHitRatio(Duration resetInterval) {
        this(resetInterval, Clock.defaultClock());
    }

    ResetPeriodicallyHitRatio(Duration resetInterval, Clock clock) {
        if (resetInterval.isNegative() || resetInterval.isZero()) {
            throw new IllegalArgumentException("intervalBetweenChunkResetting must be a positive duration");
        }
        this.resetIntervalMillis = resetInterval.toMillis();
        this.clock = clock;
        this.nextResetTimeMillisRef = new AtomicLong(clock.getTime() + resetIntervalMillis);
    }

    @Override
    public void update(int hitCount, int totalCount) {
        long nextResetTimeMillis = nextResetTimeMillisRef.get();
        long currentTimeMillis = clock.getTime();
        if (currentTimeMillis >= nextResetTimeMillis) {
            if (nextResetTimeMillisRef.compareAndSet(nextResetTimeMillis, Long.MAX_VALUE)) {
                ratio.set(0L);
                nextResetTimeMillisRef.set(currentTimeMillis + resetIntervalMillis);
            }
        }
        HitRatioUtil.updateRatio(ratio, hitCount, totalCount);
    }

    @Override
    public double getHitRatio() {
        long nextResetTimeMillis = nextResetTimeMillisRef.get();
        long currentTimeMillis = clock.getTime();
        if (currentTimeMillis >= nextResetTimeMillis) {
            if (nextResetTimeMillisRef.compareAndSet(nextResetTimeMillis, Long.MAX_VALUE)) {
                ratio.set(0L);
                nextResetTimeMillisRef.set(currentTimeMillis + resetIntervalMillis);
            }
            return Double.NaN;
        } else {
            return HitRatioUtil.getRatio(ratio.get());
        }
    }

}