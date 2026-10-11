package org.nexus.bridge.handler;

import org.nexus.bridge.BridgeConfig;
import org.nexus.bridge.BurnRequest;
import org.nexus.bridge.BridgeException;
import org.nexus.bridge.LockRequest;
import org.nexus.bridge.MintRequest;
import org.nexus.bridge.UnlockRequest;
import org.nexus.bridge.model.BridgeTransaction;
import org.nexus.bridge.keyvault.KeyVault;
import org.web3j.crypto.Credentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

import java.util.Arrays;
import java.util.List;

/**
 * BSC 桥处理器，实现 BNB Smart Chain (BSC) 网络上的跨链操作。
 *
 * <p>本处理器通过 Web3j 与 BSC 节点通信，
 * 调用部署在 BSC 上的 NexusChain 桥合约完成锁定、铸造、销毁、解锁操作。</p>
 *
 * <h2>合约交互</h2>
 * <ul>
 *   <li>桥合约地址由配置项 {@code nexus.bridge.bsc.contract-address} 指定</li>
 *   <li>NEX 代币合约地址由配置项 {@code nexus.bridge.bsc.nexus-token-address} 指定</li>
 *   <li>BSC 兼容 EVM，合约接口与以太坊一致</li>
 * </ul>
 *
 * <h2>BSC 特性</h2>
 * <ul>
 *   <li>出块时间约 3 秒，确认速度比以太坊快</li>
 *   <li>Gas 费用较低，适合大额跨链</li>
 *   <li>建议确认数：12 个区块</li>
 * </ul>
 *
 * @since 1.0.0
 */
public class BSCBridgeHandler extends AbstractBridgeHandler {

    private static final Logger log = LoggerFactory.getLogger(BSCBridgeHandler.class);

    /** BSC 链 ID。 */
    private static final String CHAIN_ID = "bsc";

    /** BSC 桥合约地址。 */
    private String contractAddress;

    /** 目标链桥合约（未配置回退 contractAddress，见全参构造器）。 */
    private String targetContractAddress;

    /** NEX 代币合约地址。 */
    private String nexusTokenAddress;

    /** RPC 端点 URL。 */
    private String rpcEndpoint;

    /** BSC 推荐确认数。 */
    private static final int RECOMMENDED_CONFIRMATIONS = 12;

    /** Web3j 客户端实例（懒加载）。 */
    private volatile Web3j web3j;

    /**
     * 构造 BSC 桥处理器。
     *
     * @param config 桥配置
     */
    public BSCBridgeHandler(BridgeConfig config) {
        super(config);
    }

    /**
     * 全参数构造器（支持真实交易发送，2026-10-11 补齐）。
     *
     * <p>此前本类只有 (config) 与 (config, contractAddress, nexusTokenAddress, rpcEndpoint)
     * 两个构造器——**无法注入 Credentials**，于是无论怎么配置都只能走
     * submitContractCall 的"无凭证"分支（非 mock 模式直接抛 CHAIN_EXECUTION_UNAVAILABLE）。
     * 本构造器与 EthereumBridgeHandler 同参数序，使桥的链上执行覆盖从 1 条链扩到多条。</p>
     *
     * <p>BSC 侧四种操作共用同一桥合约（contractAddress）；targetContractAddress
     * 仅为与 Ethereum 形态对齐保留，未配置即回退 contractAddress。</p>
     */
    public BSCBridgeHandler(org.nexus.bridge.BridgeConfig config, String sourceContractAddress,
                  String targetContractAddress, String nexusTokenAddress,
                  String rpcEndpoint, Credentials credentials,
                  Long evmChainId, KeyVault keyVault, String relayerValidatorId) {
        super(config);
        this.contractAddress = sourceContractAddress;
        this.targetContractAddress = targetContractAddress != null && !targetContractAddress.isBlank()
                ? targetContractAddress : sourceContractAddress;
        this.nexusTokenAddress = nexusTokenAddress;
        this.rpcEndpoint = rpcEndpoint;
        this.credentials = credentials;
        this.evmChainId = evmChainId != null
                ? java.math.BigInteger.valueOf(evmChainId)
                : java.math.BigInteger.valueOf(56);
        this.keyVault = keyVault;
        this.relayerValidatorId = relayerValidatorId;
    }

    /**
     * 构造 BSC 桥处理器（带合约地址）。
     *
     * @param config            桥配置
     * @param contractAddress   桥合约地址
     * @param nexusTokenAddress  NEX 代币合约地址
     * @param rpcEndpoint       RPC 端点 URL
     */
    public BSCBridgeHandler(BridgeConfig config, String contractAddress,
                            String nexusTokenAddress, String rpcEndpoint) {
        super(config);
        this.contractAddress = contractAddress;
        this.nexusTokenAddress = nexusTokenAddress;
        this.rpcEndpoint = rpcEndpoint;
    }

    @Override
    public String getChainId() {
        return CHAIN_ID;
    }

    @Override
    protected String submitLockTransaction(LockRequest request) throws BridgeException {
        log.info("Submitting lock transaction on BSC: amount={}, user={}",
                request.getAmount(), request.getUserAddress());
        ensureWeb3j();
        // lock(address user, address target, uint256 amount)
        List<org.web3j.abi.datatypes.Type> params = Arrays.asList(
                toAddress(request.getUserAddress()),
                toAddress(request.getTargetAddress()),
                toUint(request.getAmount())
        );
        String encoded = encodeFunctionCall("lock", params);
        return submitContractCall(web3j, contractAddress, encoded, CHAIN_ID);
    }

    @Override
    protected String submitMintTransaction(MintRequest request,
                                            BridgeTransaction lockTx) throws BridgeException {
        log.info("Submitting mint transaction on BSC: lockTxId={}, minter={}",
                request.getLockTxId(), request.getMinterAddress());
        ensureWeb3j();
        // mint(bytes32 lockTxId, address user, uint256 amount, address target)
        List<org.web3j.abi.datatypes.Type> params = Arrays.asList(
                toBytes32(request.getLockTxId()),
                toAddress(lockTx.getUserAddress()),
                toUint(lockTx.getAmount()),
                toAddress(lockTx.getTargetAddress())
        );
        String encoded = encodeFunctionCall("mint", params);
        return submitContractCall(web3j, contractAddress, encoded, CHAIN_ID);
    }

    @Override
    protected String submitBurnTransaction(BurnRequest request) throws BridgeException {
        log.info("Submitting burn transaction on BSC: amount={}, user={}",
                request.getAmount(), request.getUserAddress());
        ensureWeb3j();
        // burn(address user, address target, uint256 amount)
        List<org.web3j.abi.datatypes.Type> params = Arrays.asList(
                toAddress(request.getUserAddress()),
                toAddress(request.getTargetAddress()),
                toUint(request.getAmount())
        );
        String encoded = encodeFunctionCall("burn", params);
        return submitContractCall(web3j, contractAddress, encoded, CHAIN_ID);
    }

    @Override
    protected String submitUnlockTransaction(UnlockRequest request,
                                              BridgeTransaction burnTx) throws BridgeException {
        log.info("Submitting unlock transaction on BSC: burnTxId={}, unlocker={}",
                request.getBurnTxId(), request.getUnlockerAddress());
        ensureWeb3j();
        // unlock(bytes32 burnTxId, address user, uint256 amount, address target)
        List<org.web3j.abi.datatypes.Type> params = Arrays.asList(
                toBytes32(request.getBurnTxId()),
                toAddress(burnTx.getUserAddress()),
                toUint(burnTx.getAmount()),
                toAddress(burnTx.getTargetAddress())
        );
        String encoded = encodeFunctionCall("unlock", params);
        return submitContractCall(web3j, contractAddress, encoded, CHAIN_ID);
    }

    @Override
    public int queryTransactionStatus(String txHash) {
        log.debug("Querying transaction status on BSC: txHash={}", txHash);
        ensureWeb3j();
        // BSC 出块快，推荐确认数为 12
        return queryConfirmations(web3j, txHash);
    }

    /**
     * 等待交易达到 BSC 推荐确认数（12）。
     *
     * @param txHash 交易哈希
     * @return 达到确认数返回 true；超时或失败返回 false
     */
    public boolean awaitConfirmations(String txHash) {
        ensureWeb3j();
        // BSC 出块快，轮询 1.5 秒，超时 10 分钟
        return waitForConfirmations(web3j, txHash, RECOMMENDED_CONFIRMATIONS,
                1_500L, 10 * 60_000L);
    }

    /**
     * 获取 BSC 推荐确认数。
     *
     * @return 推荐确认数
     */
    public int getRecommendedConfirmations() {
        return RECOMMENDED_CONFIRMATIONS;
    }

    /**
     * 懒加载 Web3j 客户端。
     *
     * @throws BridgeException 如果 RPC 端点未配置
     */
    private void ensureWeb3j() throws BridgeException {
        if (web3j != null) {
            return;
        }
        if (rpcEndpoint == null || rpcEndpoint.isEmpty()) {
            throw new BridgeException("RPC_NOT_CONFIGURED",
                    "BSC RPC endpoint is not configured");
        }
        synchronized (this) {
            if (web3j == null) {
                web3j = Web3j.build(new HttpService(rpcEndpoint));
                log.info("Initialized Web3j client for BSC: {}", rpcEndpoint);
            }
        }
    }

    // ==================== Getter / Setter ====================

    public String getContractAddress() {
        return contractAddress;
    }

    public void setContractAddress(String contractAddress) {
        this.contractAddress = contractAddress;
    }

    public String getCpayTokenAddress() {
        return nexusTokenAddress;
    }

    public void setCpayTokenAddress(String nexusTokenAddress) {
        this.nexusTokenAddress = nexusTokenAddress;
    }

    public String getRpcEndpoint() {
        return rpcEndpoint;
    }

    public void setRpcEndpoint(String rpcEndpoint) {
        this.rpcEndpoint = rpcEndpoint;
        this.web3j = null;
    }
}
