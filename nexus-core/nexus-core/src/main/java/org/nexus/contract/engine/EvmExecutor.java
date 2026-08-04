package org.nexus.contract.engine;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * EVM 兼容层合约执行器骨架实现。
 *
 * <p>用于在 NexusChain 上执行以太坊字节码兼容的合约，
 * 便于复用 EVM 生态工具链。当前为骨架实现。</p>
 *
 * @since 1.2
 */
@Component
public class EvmExecutor implements ContractExecutor {

    @Override
    public String deploy(byte[] code, String abi) {
        // TODO: 接入 EVM 执行器（如 EthereumJ / Besu 内核），按 EVM 规则部署合约
        throw new UnsupportedOperationException("EvmExecutor.deploy: not yet implemented");
    }

    @Override
    public ExecutionResult call(String address, String method, List<Object> args) {
        // TODO: 按 EVM 调用约定执行状态变更方法
        return ExecutionResult.failure("EvmExecutor.call: not yet implemented", 0L);
    }

    @Override
    public ExecutionResult query(String address, String method, List<Object> args) {
        // TODO: 按 EVM 调用约定执行只读方法
        return ExecutionResult.failure("EvmExecutor.query: not yet implemented", 0L);
    }
}