package org.nexus.bridge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 桥链端点与中继者凭证配置（P0 修复 2026-10-09）。
 *
 * <p><b>背景：</b>{@code nexus-bridge/application.yml} 里的 {@code nexus.bridge.ethereum} /
 * {@code bsc} / {@code polygon} / {@code avalanche} 段落（{@code rpc-endpoint} + {@code chain-id}）
 * 此前**没有任何类绑定** —— 配置写着、代码没读。桥的链上执行（handler/adapter）
 * 因此从未被装配过：{@code mint}/{@code unlock} 只改数据库并标 {@code MINTED}/{@code UNLOCKED}，
 * {@code targetTxHash} 恒为 null（"报告已铸造但链上什么都没发生"）。</p>
 *
 * <h2>本类绑定</h2>
 * <p>在既有 {@code rpc-endpoint} / {@code chain-id} 之外，新增桥上链所需的
 * 合约地址与中继者私钥（生产环境经环境变量 / 配置中心注入，**不得硬编码提交**）：</p>
 * <pre>
 * nexus.bridge.ethereum:
 *   rpc-endpoint: https://...
 *   chain-id: "0x1"
 *   evm-chain-id: 1                        # 数值 chainId（EIP-155 签名用）
 *   contract-address: "0x..."              # 桥源合约（BridgeSource）
 *   target-contract-address: "0x..."       # 桥目标合约（BridgeTarget，缺省回退 contract-address）
 *   nexus-token-address: "0x..."           # NEX 代币合约
 *   relayer-private-key: ${NEX_BRIDGE_RELAYER_KEY:}   # 中继者 EVM 私钥（hex）
 * </pre>
 *
 * <p><b>就绪判据</b>：{@link Chain#isOnChainReady()} 要求 rpc / 合约 / 私钥三者齐备；
 * 任一缺失即视为该链"不可上链"，由 {@code BridgeOnChainExecutor} 与
 * {@code BridgeServiceImpl} 按 fail-closed 处理（{@code mockMode=false} 时拒绝操作，
 * 而不是静默标成功）。</p>
 */
@Component
@ConfigurationProperties(prefix = "nexus.bridge")
public class BridgeChainProperties {

    private Chain ethereum = new Chain();
    private Chain bsc = new Chain();
    private Chain polygon = new Chain();
    private Chain avalanche = new Chain();

    public Chain getEthereum() { return ethereum; }
    public void setEthereum(Chain ethereum) { this.ethereum = ethereum; }
    public Chain getBsc() { return bsc; }
    public void setBsc(Chain bsc) { this.bsc = bsc; }
    public Chain getPolygon() { return polygon; }
    public void setPolygon(Chain polygon) { this.polygon = polygon; }
    public Chain getAvalanche() { return avalanche; }
    public void setAvalanche(Chain avalanche) { this.avalanche = avalanche; }

    /** 单条链的端点与上链凭证。 */
    public static class Chain {

        /** Web3j HttpService 连接地址。 */
        private String rpcEndpoint;

        /** 链 ID（字符串形式，如 "0x1"）；仅作展示/日志用途。 */
        private String chainId;

        /** 数值 chainId（EIP-155 签名用；缺省由 handler 回退到主网 1）。 */
        private Long evmChainId;

        /** 桥源合约地址（BridgeSource）：lock/unlock 所在合约。 */
        private String contractAddress;

        /** 桥目标合约地址（BridgeTarget）：mint/burn 所在合约；缺省回退 contract-address。 */
        private String targetContractAddress;

        /** NEX 代币合约地址。 */
        private String nexusTokenAddress;

        /** 中继者 EVM 私钥（hex）；生产经环境变量注入，禁止硬编码。 */
        private String relayerPrivateKey;

        /**
         * 是否具备真实上链能力：rpc 端点 + 合约地址 + 中继者私钥三者齐备。
         *
         * <p>任一缺失即视为不可上链（fail-closed 由调用方处理）。</p>
         */
        public boolean isOnChainReady() {
            return notBlank(rpcEndpoint) && notBlank(contractAddress) && notBlank(relayerPrivateKey);
        }

        private static boolean notBlank(String s) {
            return s != null && !s.isBlank();
        }

        public String getRpcEndpoint() { return rpcEndpoint; }
        public void setRpcEndpoint(String rpcEndpoint) { this.rpcEndpoint = rpcEndpoint; }
        public String getChainId() { return chainId; }
        public void setChainId(String chainId) { this.chainId = chainId; }
        public Long getEvmChainId() { return evmChainId; }
        public void setEvmChainId(Long evmChainId) { this.evmChainId = evmChainId; }
        public String getContractAddress() { return contractAddress; }
        public void setContractAddress(String contractAddress) { this.contractAddress = contractAddress; }
        public String getTargetContractAddress() { return targetContractAddress; }
        public void setTargetContractAddress(String targetContractAddress) {
            this.targetContractAddress = targetContractAddress;
        }
        public String getNexusTokenAddress() { return nexusTokenAddress; }
        public void setNexusTokenAddress(String nexusTokenAddress) { this.nexusTokenAddress = nexusTokenAddress; }
        public String getRelayerPrivateKey() { return relayerPrivateKey; }
        public void setRelayerPrivateKey(String relayerPrivateKey) { this.relayerPrivateKey = relayerPrivateKey; }
    }
}
