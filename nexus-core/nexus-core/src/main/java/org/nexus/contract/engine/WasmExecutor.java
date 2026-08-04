package org.nexus.contract.engine;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * WASM 运行时合约执行器骨架实现。
 *
 * <p>基于 WASM runtime（Wasmer / Wasmtime）执行 NexusChain 原生合约。
 * 当前为骨架实现，留待后续接入真实 WASM 引擎。</p>
 *
 * @since 1.2
 */
@Component
public class WasmExecutor implements ContractExecutor {

    @Override
    public String deploy(byte[] code, String abi) {
        // TODO: 接入 WASM runtime，实例化合约并持久化到状态树
        throw new UnsupportedOperationException("WasmExecutor.deploy: not yet implemented");
    }

    @Override
    public ExecutionResult call(String address, String method, List<Object> args) {
        // TODO: 加载已部署的 WASM 模块，按 method 执行状态变更调用
        return ExecutionResult.failure("WasmExecutor.call: not yet implemented", 0L);
    }

    @Override
    public ExecutionResult query(String address, String method, List<Object> args) {
        // TODO: 加载已部署的 WASM 模块，按 method 执行只读查询
        return ExecutionResult.failure("WasmExecutor.query: not yet implemented", 0L);
    }
}