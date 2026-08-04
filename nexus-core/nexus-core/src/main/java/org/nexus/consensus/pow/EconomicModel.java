/*
 * Copyright (c) 2018-2026 NexusChain contributors.
 * This file is part of the java-nexuscore.
 *
 * The java-nexuscore is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The java-nexuscore is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with the java-nexuscore. If not, see <http://www.gnu.org/licenses/>.
 */

package org.nexus.consensus.pow;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.nexus.core.state.EraLinkedStateFactory;


@Component
public class EconomicModel {

    /** Smallest unit of NEX (1 NEX = 100,000,000 satoshi-equivalents). */
    public static final long NEX = 100_000_000L;

    /** Initial block reward in smallest units: 20 NEX per block. */
    public static final long INITIAL_SUPPLY = 20 * NEX;

    /**
     * Halving period in blocks (~2,102,400 blocks).
     * At 30 s block interval this is ~2 years; at 10 s it is ~8 months.
     */
    public static final long HALF_PERIOD = 1_051_200 * 2;

    /**
     * Per-era reward decay factor.
     * reward(n+1) = reward(n) * 52,218,182 / 100,000,000 ≈ reward(n) * 0.522
     * A value > 0.5 means each era retains more supply than a standard Bitcoin-style halving,
     * resulting in a gentler decay curve.
     */
    public static final long HALF_NUMERATOR   = 52_218_182L;
    public static final long HALF_DENOMINATOR = 100_000_000L;

    /**
     * Maximum eras to iterate when computing total supply.
     * After ~70 eras (at 0.522x per era) the per-block reward drops below 1 satoshi
     * and truncates to 0 in integer arithmetic. This cap prevents an unbounded loop
     * in getTotalSupply().
     */
    private static final long MAX_ERAS = 128;

    @Value("${nexus.consensus.block-interval}")
    private int blockInterval;

    @Value("${nexus.block-interval-switch-era}")
    private long blockIntervalSwitchEra;

    @Value("${nexus.block-interval-switch-to}")
    private int blockIntervalSwitchTo;

    @Value("${nexus.consensus.blocks-per-era}")
    private int blocksPerEra;

    /**
     * Returns the consensus reward (in smallest units) for a block at the given height.
     */
    public long getConsensusRewardAtHeight(long height) {
        long era = height / HALF_PERIOD;
        long reward = INITIAL_SUPPLY;
        for (long i = 0; i < era; i++) {
            reward = reward * HALF_NUMERATOR / HALF_DENOMINATOR;
        }
        if (blockIntervalSwitchEra >= 0
                && EraLinkedStateFactory.getEraAtBlockNumber(height, blocksPerEra) >= blockIntervalSwitchEra) {
            return reward * blockIntervalSwitchTo / blockInterval;
        }
        return reward;
    }

    /**
     * Prints per-era rewards (for diagnostic use).
     */
    public static void printRewardPerEra() {
        for (long reward = INITIAL_SUPPLY; reward > 0; reward = reward * HALF_NUMERATOR / HALF_DENOMINATOR) {
            System.out.println(reward * 1.0 / NEX);
        }
    }

    /**
     * Returns the asymptotic total supply in smallest units.
     *
     * Calculated via the geometric-series closed form per era:
     *   total = HALF_PERIOD * INITIAL_SUPPLY / (1 - HALF_NUMERATOR / HALF_DENOMINATOR)
     *
     * At default parameters:
     *   HALF_PERIOD              = 2,102,400 blocks
     *   INITIAL_SUPPLY           = 2,000,000,000 (in satoshi)
     *   decay                    = 0.52218182
     *   asymptotic total supply  ≈ 8.8 * 10^15 satoshi ≈ 88 million NEX
     *
     * This uses the closed form instead of an unbounded loop;
     * after ~70 eras integer truncation brings per-block reward to 0
     * so the actual on-chain total will be slightly lower.
     */
    public long getTotalSupply() {
        long denom = HALF_DENOMINATOR - HALF_NUMERATOR; // ≈ 47,781,818
        if (denom <= 0) {
            throw new IllegalStateException(
                    "HALF_NUMERATOR >= HALF_DENOMINATOR would cause infinite supply");
        }
        // total = HALF_PERIOD * INITIAL_SUPPLY / (1 - ratio)
        //        = HALF_PERIOD * INITIAL_SUPPLY * HALF_DENOMINATOR / denom
        long numerator = HALF_PERIOD * INITIAL_SUPPLY;
        return numerator / denom * HALF_DENOMINATOR;
    }

    /**
     * Returns the total supply in NEX (human-readable units).
     */
    public double getTotalSupplyNex() {
        return getTotalSupply() * 1.0 / NEX;
    }
}