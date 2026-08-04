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