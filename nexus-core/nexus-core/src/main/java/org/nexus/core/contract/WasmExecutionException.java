package org.nexus.core.contract;

/**
 * Thrown when WASM contract execution fails (compile error, runtime trap, gas exhaustion, etc.).
 */
public class WasmExecutionException extends RuntimeException {
    public WasmExecutionException(String msg) { super(msg); }
    public WasmExecutionException(String msg, Throwable cause) { super(msg, cause); }
}
