package org.nexus.contract.engine;

import org.nexus.core.contract.ContractRegistry;
import org.nexus.core.contract.ContractStatus;
import org.nexus.core.contract.GasMeter;
import org.nexus.core.contract.RegisteredContract;
import org.nexus.core.contract.WasmEngine;
import org.nexus.core.contract.WasmExecutionException;
import org.nexus.core.contract.WasmInstance;
import org.nexus.crypto.HashUtil;
import org.nexus.util.ByteUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * WASM 合约执行器真实实现。
 *
 * <p>基于 {@link WasmEngine}（Chicory 纯 Java WASM 解释器）执行 NexusChain
 * 原生合约：</p>
 * <ul>
 *   <li>{@link #deploy}：校验字节码 → 计算 gas 与合约地址（sha256 前 20 字节）→
 *       注册到 {@link ContractRegistry}（LevelDB 持久化）</li>
 *   <li>{@link #call}：加载已部署模块，按 method 执行状态变更调用并计费</li>
 *   <li>{@link #query}：加载已部署模块，按 method 执行只读查询
 *       （解释器本身无持久状态，query 不改变任何链上存储）</li>
 * </ul>
 *
 * <p>参数 ABI 约定（v1）：每个实参编码为 8 字节小端 i64；返回值按同样规则
 * 解码为 long 数组。支持 Number / 数字字符串 / Boolean 实参。</p>
 *
 * @since 1.2
 */
@Component
public class WasmExecutor implements ContractExecutor {

    private static final Logger logger = LoggerFactory.getLogger(WasmExecutor.class);

    private final WasmEngine wasmEngine;

    @Autowired(required = false)
    private ContractRegistry contractRegistry;

    @Value("${nexus.chain-id:0}")
    private int chainId;

    @Value("${nexus.contract.deploy-gas-cap:10000000}")
    private long deployGasCap;

    @Value("${nexus.contract.call-gas-cap:1000000}")
    private long callGasCap;

    public WasmExecutor(WasmEngine wasmEngine) {
        this.wasmEngine = wasmEngine;
    }

    @Override
    public String deploy(byte[] code, String abi) {
        if (code == null || code.length == 0) {
            throw new IllegalArgumentException("contract bytecode is empty");
        }
        if (!wasmEngine.validate(code)) {
            throw new IllegalArgumentException("invalid WASM bytecode (structure validation failed)");
        }
        if (contractRegistry == null) {
            throw new IllegalStateException("contract registry unavailable");
        }

        // Deployment gas: base fee + per-byte fee
        GasMeter deployMeter = new GasMeter(deployGasCap);
        try {
            deployMeter.consumeDeployment(code.length);
        } catch (GasMeter.OutOfGasException e) {
            throw new IllegalStateException("deployment gas exceeded: " + e.getMessage(), e);
        }

        // Contract address: first 20 bytes of sha256(bytecode), hex with 0x prefix
        byte[] codeHashBytes = HashUtil.sha256(code);
        String codeHash = "0x" + ByteUtil.toHexString(codeHashBytes);
        byte[] addressBytes = new byte[20];
        System.arraycopy(codeHashBytes, 0, addressBytes, 0, 20);
        String address = "0x" + ByteUtil.toHexString(addressBytes);

        if (contractRegistry.exists(address)) {
            throw new IllegalStateException("contract already deployed: " + address);
        }

        String wasmCodeHex = "0x" + ByteUtil.toHexString(code);
        long createdAt = System.currentTimeMillis() / 1000L;
        RegisteredContract rc = new RegisteredContract(
                address, "wasm-contract", abi == null ? "[]" : abi,
                codeHash, wasmCodeHex, "", 0L, createdAt, chainId, ContractStatus.ACTIVE);

        boolean registered = contractRegistry.register(rc);
        if (!registered) {
            throw new IllegalStateException("contract registration failed: " + address);
        }

        logger.info("Contract deployed: address={}, codeHash={}, gasUsed={}, bytes={}",
                address, codeHash, deployMeter.getGasUsed(), code.length);
        return address;
    }

    @Override
    public ExecutionResult call(String address, String method, List<Object> args) {
        return execute(address, method, args, true);
    }

    @Override
    public ExecutionResult query(String address, String method, List<Object> args) {
        return execute(address, method, args, false);
    }

    /**
     * Shared execution path for call / query.
     *
     * @param stateMutating true for call (charged normally); query executes with
     *                      the same interpreter but is documented as read-only
     */
    private ExecutionResult execute(String address, String method, List<Object> args, boolean stateMutating) {
        if (address == null || address.isEmpty()) {
            return ExecutionResult.failure("contract address is required", 0L);
        }
        if (method == null || method.isEmpty()) {
            return ExecutionResult.failure("method name is required", 0L);
        }
        if (contractRegistry == null) {
            return ExecutionResult.failure("contract registry unavailable", 0L);
        }

        GasMeter meter = new GasMeter(callGasCap);
        try {
            WasmInstance instance = wasmEngine.load(address, contractRegistry, meter);
            byte[] argBytes = encodeArgs(args);
            byte[] resultBytes = instance.call(method, argBytes);
            long gasUsed = instance.getGasUsed();

            long[] decoded = decodeResults(resultBytes);
            Object returnValue = decoded.length == 1 ? decoded[0] : decoded;
            List<String> logs = List.of(
                    (stateMutating ? "CALL" : "QUERY") + " " + method + " gas=" + gasUsed);
            return ExecutionResult.success(returnValue, gasUsed, logs);
        } catch (WasmExecutionException e) {
            logger.warn("Contract execution failed: address={}, method={}: {}",
                    address, method, e.getMessage());
            return ExecutionResult.failure(e.getMessage(), meter.getGasUsed());
        } catch (Exception e) {
            logger.error("Contract execution error: address={}, method={}", address, method, e);
            return ExecutionResult.failure("execution error: " + e.getMessage(), meter.getGasUsed());
        }
    }

    /**
     * Encode argument list as concatenated little-endian i64 words.
     */
    private byte[] encodeArgs(List<Object> args) {
        if (args == null || args.isEmpty()) {
            return new byte[0];
        }
        ByteBuffer buf = ByteBuffer.allocate(args.size() * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (Object arg : args) {
            buf.putLong(toLong(arg));
        }
        return buf.array();
    }

    /**
     * Decode result bytes (little-endian i64 words) into long array.
     */
    private long[] decodeResults(byte[] resultBytes) {
        if (resultBytes == null || resultBytes.length == 0) {
            return new long[0];
        }
        ByteBuffer buf = ByteBuffer.wrap(resultBytes).order(ByteOrder.LITTLE_ENDIAN);
        long[] results = new long[resultBytes.length / 8];
        for (int i = 0; i < results.length && buf.remaining() >= 8; i++) {
            results[i] = buf.getLong();
        }
        return results;
    }

    private long toLong(Object arg) {
        if (arg == null) {
            return 0L;
        }
        if (arg instanceof Number) {
            if (arg instanceof BigDecimal || arg instanceof BigInteger) {
                return new BigDecimal(arg.toString()).longValueExact();
            }
            return ((Number) arg).longValue();
        }
        if (arg instanceof Boolean) {
            return ((Boolean) arg) ? 1L : 0L;
        }
        String s = arg.toString().trim();
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            try {
                return new BigDecimal(s).longValueExact();
            } catch (ArithmeticException | NumberFormatException ex) {
                throw new IllegalArgumentException("unsupported argument value: " + s);
            }
        }
    }
}
