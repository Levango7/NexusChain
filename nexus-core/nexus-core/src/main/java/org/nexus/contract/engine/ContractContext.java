package org.nexus.contract.engine;

import java.math.BigInteger;

/**
 * 合约执行上下文。
 *
 * <p>封装一次合约调用所需的链上环境信息，
 * 供执行引擎在沙箱内访问。</p>
 *
 * @since 1.2
 */
public class ContractContext {

    /** 合约地址（hex） */
    private String contractAddress;

    /** 调用者地址（hex） */
    private String callerAddress;

    /** 当前区块号 */
    private BigInteger blockNumber;

    /** 当前区块时间戳（毫秒） */
    private long timestamp;

    /** 本次调用 gas 上限 */
    private long gasLimit;

    /**
     * 最终性查询端口（ADR-030 M5 连接轴）。
     *
     * <p>合约通过它判断某高度的检查点是否已被 NexFinality 最终化，
     * 实现「结算合约必须等终局才放款」的语义。由共识层在执行前注入；
     * 未注入时 {@link #isFinalized(long)} 返回 {@code false}（fail-closed）。</p>
     */
    private FinalityOracle finalityOracle;

    /**
     * 区块最终性查询函数接口。
     */
    @FunctionalInterface
    public interface FinalityOracle {
        /**
         * @param checkpointHeight 检查点高度
         * @return 该高度是否已最终化（≥2/3 质押权重投票通过）
         */
        boolean isFinalized(long checkpointHeight);
    }

    /**
     * 判断指定高度的检查点是否已最终化。
     *
     * <p>fail-closed：未注入 {@link FinalityOracle}（如最终性层未启用）时返回 {@code false}，
     * 合约不应把"无法确认"当作"已最终化"处理。</p>
     *
     * @param checkpointHeight 检查点高度
     * @return 已最终化返回 true
     */
    public boolean isFinalized(long checkpointHeight) {
        return finalityOracle != null && finalityOracle.isFinalized(checkpointHeight);
    }

    public void setFinalityOracle(FinalityOracle finalityOracle) {
        this.finalityOracle = finalityOracle;
    }

    public ContractContext() {
    }

    public ContractContext(String contractAddress, String callerAddress,
                           BigInteger blockNumber, long timestamp, long gasLimit) {
        this.contractAddress = contractAddress;
        this.callerAddress = callerAddress;
        this.blockNumber = blockNumber;
        this.timestamp = timestamp;
        this.gasLimit = gasLimit;
    }

    public String getContractAddress() {
        return contractAddress;
    }

    public void setContractAddress(String contractAddress) {
        this.contractAddress = contractAddress;
    }

    public String getCallerAddress() {
        return callerAddress;
    }

    public void setCallerAddress(String callerAddress) {
        this.callerAddress = callerAddress;
    }

    public BigInteger getBlockNumber() {
        return blockNumber;
    }

    public void setBlockNumber(BigInteger blockNumber) {
        this.blockNumber = blockNumber;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public long getGasLimit() {
        return gasLimit;
    }

    public void setGasLimit(long gasLimit) {
        this.gasLimit = gasLimit;
    }
}