package org.nexus.contract.engine;

import org.nexus.core.contract.ContractRegistry;
import org.nexus.core.contract.ContractStatus;
import org.nexus.core.contract.ContractStorage;
import org.nexus.core.contract.EvmInterpreter;
import org.nexus.core.contract.GasMeter;
import org.nexus.core.contract.RegisteredContract;
import org.nexus.core.contract.WasmExecutionException;
import org.nexus.crypto.HashUtil;
import org.nexus.util.ByteUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * EVM 兼容层合约执行器实现。
 *
 * <p>基于内嵌 {@link EvmInterpreter}（自研栈式 EVM 子集，无外部 EVM 依赖）
 * 执行以太坊字节码兼容的合约：</p>
 * <ul>
 *   <li>{@link #deploy}：校验字节码 → 计算合约地址（sha256 前 20 字节）→
 *       注册到 {@link ContractRegistry}（LevelDB 持久化）</li>
 *   <li>{@link #call}：加载字节码，将实参按 EVM 栈序压栈（首参为栈顶），
 *       从 PC 0 执行，持久化 {@link ContractStorage} 存储变更</li>
 *   <li>{@link #query}：与 call 相同执行路径，但执行前的存储快照执行后
 *       <b>不写回</b>（只读语义，不影响链上存储）</li>
 * </ul>
 *
 * <p>调用 ABI 约定（v1，最小子集）：{@code method} 参数被接受但不做函数分发
 * （无函数选择器表），字节码从 PC 0 线性执行；实参逆序压栈使首个实参位于栈顶。
 * 返回值取 {@code RETURN} 指令执行后的栈顶值。</p>
 *
 * @since 1.2
 */
@Component
public class EvmExecutor implements ContractExecutor {

    private static final Logger logger = LoggerFactory.getLogger(EvmExecutor.class);

    @Autowired(required = false)
    private ContractRegistry contractRegistry;

    @Autowired(required = false)
    private ContractStorage contractStorage;

    @Value("${nexus.chain-id:0}")
    private int chainId;

    @Value("${nexus.contract.evm-gas-cap:1000000}")
    private long evmGasCap;

    @Override
    public String deploy(byte[] code, String abi) {
        if (code == null || code.length == 0) {
            throw new IllegalArgumentException("contract bytecode is empty");
        }
        if (contractRegistry == null) {
            throw new IllegalStateException("contract registry unavailable");
        }

        // Deployment gas: base fee + per-byte fee
        GasMeter deployMeter = new GasMeter(10_000_000L);
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

        String codeHex = "0x" + ByteUtil.toHexString(code);
        long createdAt = System.currentTimeMillis() / 1000L;
        RegisteredContract rc = new RegisteredContract(
                address, "evm-contract", abi == null ? "[]" : abi,
                codeHash, codeHex, "", 0L, createdAt, chainId, ContractStatus.ACTIVE);

        boolean registered = contractRegistry.register(rc);
        if (!registered) {
            throw new IllegalStateException("contract registration failed: " + address);
        }

        logger.info("EVM contract deployed: address={}, codeHash={}, bytes={}",
                address, codeHash, code.length);
        return address;
    }

    @Override
    public ExecutionResult call(String address, String method, List<Object> args) {
        return execute(address, args, true);
    }

    @Override
    public ExecutionResult query(String address, String method, List<Object> args) {
        return execute(address, args, false);
    }

    /**
     * Shared execution path.
     *
     * @param writeBack true for call (persist storage changes); false for query (read-only)
     */
    private ExecutionResult execute(String address, List<Object> args, boolean writeBack) {
        if (address == null || address.isEmpty()) {
            return ExecutionResult.failure("contract address is required", 0L);
        }
        if (contractRegistry == null) {
            return ExecutionResult.failure("contract registry unavailable", 0L);
        }
        if (contractStorage == null) {
            return ExecutionResult.failure("contract storage unavailable", 0L);
        }

        RegisteredContract rc = contractRegistry.getByAddress(address);
        if (rc == null) {
            return ExecutionResult.failure("contract not deployed: " + address, 0L);
        }
        if (rc.getStatus() != ContractStatus.ACTIVE) {
            return ExecutionResult.failure(
                    "contract not active: " + address + " status=" + rc.getStatus(), 0L);
        }
        byte[] code = decodeCode(rc.getWasmCode());
        if (code == null || code.length == 0) {
            return ExecutionResult.failure("contract has no bytecode: " + address, 0L);
        }

        GasMeter meter = new GasMeter(evmGasCap);
        try {
            // Snapshot current storage; interpreter operates on the copy.
            Map<BigInteger, byte[]> storageSnapshot = contractStorage.snapshot(address);

            EvmInterpreter interpreter = new EvmInterpreter(code, meter, storageSnapshot);
            interpreter.setEnv(address, "", System.currentTimeMillis(), chainId);
            pushArgs(interpreter, args);
            interpreter.run();

            long gasUsed = interpreter.getGasUsed();
            if (interpreter.isReverted()) {
                return ExecutionResult.failure(
                        "EVM execution reverted: " + interpreter.getReturnValue(), gasUsed);
            }

            // Persist storage changes only for state-mutating call.
            if (writeBack) {
                contractStorage.writeBack(address, interpreter.getStorage());
            }

            BigInteger returnValue = interpreter.getReturnValue();
            List<String> logs = List.of(
                    (writeBack ? "CALL" : "QUERY") + " gas=" + gasUsed);
            return ExecutionResult.success(returnValue, gasUsed, logs);
        } catch (WasmExecutionException e) {
            logger.warn("EVM execution failed: address={}: {}", address, e.getMessage());
            return ExecutionResult.failure(e.getMessage(), meter.getGasUsed());
        } catch (GasMeter.OutOfGasException e) {
            logger.warn("EVM out of gas: address={}", address);
            return ExecutionResult.failure("out of gas: " + e.getMessage(), meter.getGasUsed());
        } catch (RuntimeException e) {
            logger.error("EVM execution error: address={}", address, e);
            return ExecutionResult.failure("execution error: " + e.getMessage(), meter.getGasUsed());
        }
    }

    /**
     * Push args onto the interpreter stack in reverse order so the first
     * argument ends up on top (EVM stack convention).
     */
    private void pushArgs(EvmInterpreter interpreter, List<Object> args) {
        interpreter.pushArgs(args);
    }

    /**
     * Decode stored hex bytecode (with or without 0x prefix).
     */
    private byte[] decodeCode(String hex) {
        if (hex == null || hex.isEmpty()) {
            return null;
        }
        String clean = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
        try {
            return HexFormat.of().parseHex(clean);
        } catch (IllegalArgumentException e) {
            logger.warn("invalid hex bytecode: {}", e.getMessage());
            return null;
        }
    }
}
