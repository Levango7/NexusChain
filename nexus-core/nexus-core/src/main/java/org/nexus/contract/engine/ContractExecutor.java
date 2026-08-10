package org.nexus.contract.engine;

import java.util.List;

/**
 * 合约执行引擎抽象接口。
 *
 * <p>定义智能合约在 NexusChain 上的部署、调用与查询能力。
 * 实现可基于 WASM、EVM 或其他虚拟机。</p>
 *
 * <h2>操作语义</h2>
 * <ul>
 *   <li>{@link #deploy} 部署合约字节码并返回链上地址</li>
 *   <li>{@link #call} 状态变更调用，会修改链上存储</li>
 *   <li>{@link #query} 只读调用，不修改存储，不消耗 gas</li>
 * </ul>
 *
 * @since 1.2
 */
public interface ContractExecutor {

    /**
     * 部署合约到链上。
     *
     * @param code 合约字节码（WASM / EVM bytecode）
     * @param abi  合约 ABI 描述（JSON）
     * @return 部署后的合约地址（hex）
     */
    String deploy(byte[] code, String abi);

    /**
     * 状态变更调用，会修改链上存储并消耗 gas。
     *
     * @param address 合约地址（hex）
     * @param method  调用方法名
     * @param args    实参列表
     * @return 执行结果
     */
    ExecutionResult call(String address, String method, List<Object> args);

    /**
     * 只读查询调用，不修改链上状态。
     *
     * @param address 合约地址（hex）
     * @param method  查询方法名
     * @param args    实参列表
     * @return 执行结果
     */
    ExecutionResult query(String address, String method, List<Object> args);
}