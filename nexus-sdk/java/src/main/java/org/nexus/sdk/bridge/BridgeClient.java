package org.nexus.sdk.bridge;

import org.nexus.sdk.RpcClient;

import java.math.BigInteger;

/**
 * 跨链桥客户端。
 *
 * <p>提供 NexusChain 与外部链（Ethereum、BSC、Polygon 等）之间的
 * 资产跨链锁定、解锁和状态跟踪能力。</p>
 */
public class BridgeClient {

    private final RpcClient rpcClient;

    public BridgeClient(RpcClient rpcClient) {
        this.rpcClient = rpcClient;
    }

    /**
     * 锁定资产，发起跨链转移。
     *
     * @param from         发送方地址（NexusChain 侧）
     * @param token        代币符号或合约地址
     * @param amount       跨链数量（最小单位）
     * @param targetChain  目标链标识（如 "ethereum"、"bsc"、"polygon"）
     * @param targetAddress 目标链上的接收地址
     * @return 跨链交易哈希
     */
    public String lock(String from, String token, BigInteger amount,
                       String targetChain, String targetAddress) {
        // TODO: 调用桥合约锁定资产
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /**
     * 解锁资产，完成跨链接收。
     *
     * @param to           接收方地址（NexusChain 侧）
     * @param token        代币符号或合约地址
     * @param amount       解锁数量（最小单位）
     * @param sourceChain  源链标识
     * @param proof        跨链证明数据
     * @return 解锁交易哈希
     */
    public String unlock(String to, String token, BigInteger amount,
                         String sourceChain, String proof) {
        // TODO: 调用桥合约解锁资产
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /**
     * 查询跨链交易状态。
     *
     * @param txHash 源链上的交易哈希
     * @return 跨链交易状态信息
     */
    public BridgeStatus getBridgeStatus(String txHash) {
        // TODO: 查询跨链交易状态
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /**
     * 查询支持的目标链列表。
     *
     * @return 目标链标识数组
     */
    public String[] getSupportedChains() {
        // TODO: 查询桥合约支持的链
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /**
     * 查询跨链手续费。
     *
     * @param token       代币符号
     * @param targetChain 目标链标识
     * @return 手续费（NEX，最小单位 wei）
     */
    public BigInteger getBridgeFee(String token, String targetChain) {
        // TODO: 查询跨链手续费
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /**
     * 跨链交易状态。
     */
    public static class BridgeStatus {
        private final String txHash;
        private final String status;       // pending | confirmed | completed | failed
        private final String sourceChain;
        private final String targetChain;
        private final long confirmations;

        public BridgeStatus(String txHash, String status, String sourceChain,
                            String targetChain, long confirmations) {
            this.txHash = txHash;
            this.status = status;
            this.sourceChain = sourceChain;
            this.targetChain = targetChain;
            this.confirmations = confirmations;
        }

        public String getTxHash() { return txHash; }
        public String getStatus() { return status; }
        public String getSourceChain() { return sourceChain; }
        public String getTargetChain() { return targetChain; }
        public long getConfirmations() { return confirmations; }
    }
}
