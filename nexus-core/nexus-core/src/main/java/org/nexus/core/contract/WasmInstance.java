package org.nexus.core.contract;

/**
 * A running WASM contract instance with a single invocation.
 *
 * <p>Instances are single-use — after {@code call()} the fuel meter is
 * exhausted or closed and the instance should be discarded.</p>
 */
public interface WasmInstance {

    /**
     * Call a contract method.
     *
     * @param methodName  exported function name
     * @param args        serialised arguments (ABI-encoded)
     * @return serialised return value, never null
     * @throws WasmExecutionException on contract-level errors
     */
    byte[] call(String methodName, byte[] args);

    /**
     * Total gas consumed by this instance so far (in fuel units).
     */
    long getGasUsed();

    /**
     * Whether the instance has remaining gas budget.
     */
    boolean hasGasRemaining();
}
