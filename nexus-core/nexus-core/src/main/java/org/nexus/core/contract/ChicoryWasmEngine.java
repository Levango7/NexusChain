package org.nexus.core.contract;

import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Module;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chicory-backed {@link WasmEngine} implementation (pure-Java WASM interpreter).
 *
 * <p>Chicory is an embedded, sandboxed WASM interpreter with no native
 * dependencies — it runs WASM bytecode directly in the JVM. This engine wires
 * a {@link GasMeter} to Chicory's {@code ExecutionListener} so that every
 * executed instruction is charged against the fuel cap (see
 * {@link ChicoryWasmInstance}).</p>
 *
 * <p>Maintains an LRU-style in-memory cache of compiled modules keyed by
 * contract address so repeated {@link #load} calls avoid re-parsing bytecode.</p>
 *
 * @since 1.1
 */
@Component
public class ChicoryWasmEngine implements WasmEngine {

    private static final Logger logger = LoggerFactory.getLogger(ChicoryWasmEngine.class);

    /** WASM magic number: 0x00 'a' 's' 'm' */
    private static final int WASM_MAGIC = 0x6d736100;

    /** Compiled-module cache: contractAddress → Chicory runtime module. */
    private final Map<String, Module> moduleCache = new ConcurrentHashMap<>();

    @Override
    public WasmInstance instantiate(byte[] wasmCode, GasMeter gasMeter) {
        if (wasmCode == null || wasmCode.length == 0) {
            throw new WasmExecutionException("empty wasm bytecode");
        }
        if (gasMeter == null) {
            throw new WasmExecutionException("gasMeter is required");
        }
        try {
            Module module = Module.builder(ByteBuffer.wrap(wasmCode))
                    .withInitialize(true)
                    .withStart(false)
                    .withTypeValidation(true)
                    .withUnsafeExecutionListener(ChicoryWasmInstance.gasListener(gasMeter))
                    .build();
            Instance instance = module.instantiate();
            return new ChicoryWasmInstance(instance, gasMeter);
        } catch (WasmExecutionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new WasmExecutionException("wasm instantiation failed: " + e.getMessage(), e);
        }
    }

    @Override
    public WasmInstance load(String contractAddress, ContractRegistry registry, GasMeter gasMeter) {
        if (contractAddress == null || contractAddress.isEmpty()) {
            throw new WasmExecutionException("contractAddress is required");
        }
        if (registry == null) {
            throw new WasmExecutionException("registry is required");
        }
        if (gasMeter == null) {
            throw new WasmExecutionException("gasMeter is required");
        }

        RegisteredContract rc = registry.getByAddress(contractAddress);
        if (rc == null) {
            throw new WasmExecutionException("contract not registered: " + contractAddress);
        }
        if (rc.getStatus() != ContractStatus.ACTIVE) {
            throw new WasmExecutionException(
                    "contract not active: " + contractAddress + " status=" + rc.getStatus());
        }

        byte[] wasmCode = decodeWasmCode(rc.getWasmCode());
        if (wasmCode == null || wasmCode.length == 0) {
            throw new WasmExecutionException("contract has no wasm code: " + contractAddress);
        }

        // Charge a per-load base cost so loading is not free.
        try {
            gasMeter.consume(GasMeter.COST_STORAGE_READ);
        } catch (GasMeter.OutOfGasException e) {
            throw new WasmExecutionException("out of gas loading contract: " + e.getMessage(), e);
        }

        // Cache compiled module per address (re-parse only on miss).
        Module module = moduleCache.computeIfAbsent(contractAddress, addr -> {
            try {
                return Module.builder(ByteBuffer.wrap(wasmCode))
                        .withInitialize(true)
                        .withStart(false)
                        .withTypeValidation(true)
                        .withUnsafeExecutionListener(ChicoryWasmInstance.gasListener(gasMeter))
                        .build();
            } catch (RuntimeException e) {
                throw new WasmExecutionException(
                        "wasm compile failed for " + addr + ": " + e.getMessage(), e);
            }
        });

        try {
            Instance instance = module.instantiate();
            return new ChicoryWasmInstance(instance, gasMeter);
        } catch (WasmExecutionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new WasmExecutionException(
                    "wasm instantiate failed for " + contractAddress + ": " + e.getMessage(), e);
        }
    }

    @Override
    public boolean validate(byte[] wasmCode) {
        if (wasmCode == null || wasmCode.length < 8) {
            return false;
        }
        // Check the 4-byte magic number (little-endian 0x6d736100).
        int magic = (wasmCode[0] & 0xff)
                | ((wasmCode[1] & 0xff) << 8)
                | ((wasmCode[2] & 0xff) << 16)
                | ((wasmCode[3] & 0xff) << 24);
        if (magic != WASM_MAGIC) {
            logger.debug("wasm magic mismatch: got 0x{}", Integer.toHexString(magic));
            return false;
        }
        // Full structural validation by attempting a parse/build.
        try {
            Module.builder(ByteBuffer.wrap(wasmCode))
                    .withInitialize(false)
                    .withStart(false)
                    .withTypeValidation(true)
                    .build();
            return true;
        } catch (RuntimeException e) {
            logger.debug("wasm structural validation failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Decode the stored hex wasm code (with or without 0x prefix) to bytes.
     */
    private byte[] decodeWasmCode(String hex) {
        if (hex == null || hex.isEmpty()) {
            return null;
        }
        String clean = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
        try {
            return HexFormat.of().parseHex(clean);
        } catch (IllegalArgumentException e) {
            logger.warn("invalid hex wasm code: {}", e.getMessage());
            return null;
        }
    }
}
