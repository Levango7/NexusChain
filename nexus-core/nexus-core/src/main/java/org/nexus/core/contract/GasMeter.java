package org.nexus.core.contract;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Gas metering for WASM contract execution.
 *
 * <h2>Pricing table</h2>
 * <table>
 *   <tr><th>Operation</th><th>Gas cost</th></tr>
 *   <tr><td>WASM instruction (base)</td><td>1 gas</td></tr>
 *   <tr><td>Memory access (per 4 KiB)</td><td>1 gas</td></tr>
 *   <tr><td>Storage read</td><td>100 gas</td></tr>
 *   <tr><td>Storage write</td><td>500 gas</td></tr>
 *   <tr><td>Contract deployment (base fee)</td><td>100,000 gas</td></tr>
 *   <tr><td>Contract deployment (per byte)</td><td>10 gas</td></tr>
 * </table>
 */
public class GasMeter {

    /** Default per-transaction gas cap. */
    public static final long DEFAULT_GAS_CAP = 1_000_000L;

    public static final long COST_BASE_INSTRUCTION = 1L;
    public static final long COST_MEMORY_PER_4KIB   = 1L;
    public static final long COST_STORAGE_READ      = 100L;
    public static final long COST_STORAGE_WRITE     = 500L;
    public static final long COST_DEPLOY_BASE       = 100_000L;
    public static final long COST_DEPLOY_PER_BYTE   = 10L;

    private final long gasCap;
    private final AtomicLong gasUsed = new AtomicLong(0);

    public GasMeter() { this(DEFAULT_GAS_CAP); }

    public GasMeter(long gasCap) { this.gasCap = gasCap; }

    /** Consume gas for a base instruction. Throws if out of gas. */
    public void consumeInstruction(int count) {
        consume(COST_BASE_INSTRUCTION * count);
    }

    /** Consume gas for a storage read. */
    public void consumeStorageRead() { consume(COST_STORAGE_READ); }

    /** Consume gas for a storage write. */
    public void consumeStorageWrite() { consume(COST_STORAGE_WRITE); }

    /** Consume gas for contract deployment (base + per-byte). */
    public void consumeDeployment(int bytecodeLength) {
        consume(COST_DEPLOY_BASE + COST_DEPLOY_PER_BYTE * bytecodeLength);
    }

    public void consume(long amount) {
        long current = gasUsed.addAndGet(amount);
        if (current > gasCap) {
            throw new OutOfGasException("Gas limit exceeded: " + current + " / " + gasCap);
        }
    }

    public long getGasUsed() { return gasUsed.get(); }
    public long getGasCap()  { return gasCap; }
    public long getGasRemaining() { return Math.max(0, gasCap - gasUsed.get()); }
    public boolean hasGasRemaining() { return gasUsed.get() < gasCap; }

    /** Reset this meter for re-use (e.g., on re-deployment). */
    public void reset() { gasUsed.set(0); }

    /** Exception thrown when gas is exhausted — transaction MUST be rolled back. */
    public static class OutOfGasException extends RuntimeException {
        public OutOfGasException(String msg) { super(msg); }
    }
}
