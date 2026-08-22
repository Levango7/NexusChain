package org.nexus.core.contract;

import com.dylibso.chicory.runtime.ExecutionListener;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasm.types.Instruction;
import com.dylibso.chicory.wasm.types.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Chicory-backed WASM instance (single-use, fuel-metered).
 *
 * <p>Binary ABI convention (v1, document for contract authors):</p>
 * <ul>
 *   <li>Arguments: concatenated 8-byte little-endian signed i64 values</li>
 *   <li>Return: concatenated 8-byte little-endian signed i64 values</li>
 * </ul>
 *
 * <p>Gas accounting: every executed WASM instruction consumes
 * {@link GasMeter#COST_BASE_INSTRUCTION} via the {@link GasMeter} supplied at
 * construction. When the cap is exceeded the meter throws
 * {@link GasMeter.OutOfGasException}, which surfaces as
 * {@link WasmExecutionException} from {@link #call}.</p>
 */
public class ChicoryWasmInstance implements WasmInstance {

    private static final Logger logger = LoggerFactory.getLogger(ChicoryWasmInstance.class);

    private final Instance instance;
    private final GasMeter gasMeter;

    ChicoryWasmInstance(Instance instance, GasMeter gasMeter) {
        this.instance = instance;
        this.gasMeter = gasMeter;
    }

    /**
     * Build the Chicory execution listener that feeds the gas meter.
     * Package-private so {@link ChicoryWasmEngine} can attach it at module build time.
     */
    static ExecutionListener gasListener(GasMeter meter) {
        return new ExecutionListener() {
            @Override
            public void onExecution(Instruction instruction, long[] operands,
                                    com.dylibso.chicory.runtime.MStack stack) {
                meter.consumeInstruction(1);
            }
        };
    }

    @Override
    public byte[] call(String methodName, byte[] args) {
        if (methodName == null || methodName.isEmpty()) {
            throw new WasmExecutionException("method name is required");
        }
        try {
            com.dylibso.chicory.runtime.ExportFunction fn = instance.export(methodName);
            if (fn == null) {
                throw new WasmExecutionException("export not found: " + methodName);
            }
            Value[] inputs = decodeArgs(args);
            Value[] outputs = fn.apply(inputs);
            return encodeResults(outputs);
        } catch (WasmExecutionException e) {
            throw e;
        } catch (GasMeter.OutOfGasException e) {
            throw new WasmExecutionException("out of gas: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new WasmExecutionException("wasm call failed: " + methodName + ": " + e.getMessage(), e);
        }
    }

    @Override
    public long getGasUsed() {
        return gasMeter.getGasUsed();
    }

    @Override
    public boolean hasGasRemaining() {
        return gasMeter.hasGasRemaining();
    }

    /**
     * Decode the binary argument blob into i64 {@link Value}s.
     * Accepts null / empty as zero arguments.
     */
    private Value[] decodeArgs(byte[] args) {
        if (args == null || args.length == 0) {
            return Value.EMPTY_VALUES;
        }
        if (args.length % 8 != 0) {
            throw new WasmExecutionException(
                    "invalid argument blob: length " + args.length + " is not a multiple of 8");
        }
        ByteBuffer buf = ByteBuffer.wrap(args).order(ByteOrder.LITTLE_ENDIAN);
        List<Value> values = new ArrayList<>();
        while (buf.remaining() >= 8) {
            values.add(Value.i64(buf.getLong()));
        }
        return values.toArray(new Value[0]);
    }

    /**
     * Encode result values as little-endian i64 words.
     */
    private byte[] encodeResults(Value[] outputs) {
        if (outputs == null || outputs.length == 0) {
            return new byte[0];
        }
        ByteBuffer buf = ByteBuffer.allocate(outputs.length * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (Value v : outputs) {
            buf.putLong(v.asLong());
        }
        return buf.array();
    }
}
