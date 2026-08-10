package org.nexus.core.contract;

/**
 * WASM smart-contract execution engine abstraction.
 *
 * <p>Implementations wrap a WASM runtime (Wasmer, Wasmtime, etc.)
 * and expose a sandboxed execution environment.  Each call to
 * {@link #instantiate} creates a fresh instance with fuel metering.</p>
 *
 * <h2>Sandbox constraints</h2>
 * <ul>
 *   <li>File-system access — DISABLED</li>
 *   <li>Network access — DISABLED</li>
 *   <li>Clock — read-only</li>
 *   <li>Random — read-only</li>
 *   <li>Max memory — 64 MiB</li>
 * </ul>
 *
 * @since 1.1
 */
public interface WasmEngine {

    /**
     * Instantiate a WASM module from raw bytecode.
     *
     * @param wasmCode  raw WASM bytecode (uncompressed)
     * @param gasMeter  fuel meter that caps per-call gas consumption
     * @return a runnable instance
     * @throws WasmExecutionException if instantiation fails
     */
    WasmInstance instantiate(byte[] wasmCode, GasMeter gasMeter);

    /**
     * Load a previously registered contract by its on-chain address.
     * The engine is expected to maintain an LRU cache of hot contracts.
     *
     * @param contractAddress  hex address
     * @param registry         contract registry (for bytecode lookup)
     * @param gasMeter         fuel meter
     * @return a runnable instance
     */
    WasmInstance load(String contractAddress, ContractRegistry registry, GasMeter gasMeter);

    /**
     * Check if the WASM bytecode is well-formed (validates structure
     * without executing).  Called during contract deployment.
     */
    boolean validate(byte[] wasmCode);
}
