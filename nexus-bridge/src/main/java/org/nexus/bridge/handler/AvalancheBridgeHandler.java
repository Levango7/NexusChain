package org.nexus.bridge.handler;

import org.nexus.bridge.BridgeConfig;
import org.nexus.bridge.BurnRequest;
import org.nexus.bridge.BridgeException;
import org.nexus.bridge.LockRequest;
import org.nexus.bridge.MintRequest;
import org.nexus.bridge.UnlockRequest;
import org.nexus.bridge.model.BridgeTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

import java.util.Arrays;
import java.util.List;

/**
 * Avalanche C-Chain 桥处理器，实现 Avalanche C-Chain 网络上的跨链操作。
 *
 * <p>本处理器通过 Web3j 与 Avalanche C-Chain 节点通信，
 * 调用部署在 C-Chain 上的 NexusChain 桥合约完成锁定、铸造、销毁、解锁操作。</p>
 *
 * <h2>合约交互</h2>
 * <ul>
 *   <li>桥合约地址由配置项 {@code nexus.bridge.avalanche.contract-address} 指定</li>
 *   <li>NEX 代币合约地址由配置项 {@code nexus.bridge.avalanche.nexus-token-address} 指定</li>
 *   <li>C-Chain 兼容 EVM，合约接口与以太坊一致</li>
 *   <li>铸币权限需在桥合约上设置给验证者多签地址</li>
 * </ul>
 *
 * <h2>合约函数签名</h2>
 * <pre>
 *   lock(address user, address target, uint256 amount) returns (bool)
 *   mint(bytes32 lockTxId, address user, uint256 amount, address target) returns (bool)
 *   burn(address user, address target, uint256 amount) returns (bool)
 *   unlock(bytes32 burnTxId, address user, uint256 amount, address target) returns (bool)
 * </pre>
 *
 * <h2>Avalanche C-Chain 特性</h2>
 * <ul>
 *   <li>出块时间约 2 秒，Snowman 共识提供快速最终性（约 1-2 秒）</li>
 *   <li>Gas 费用以 AVAX 计价，C-Chain 使用与 EVM 一致的 {@code eth_*} JSON-RPC 命名空间</li>
 *   <li>推荐确认数：20 个区块（C-Chain 最终性快，但保守取 20 以应对网络抖动）</li>
 *   <li>Chain ID：主网 {@code 43114}（0xA86A），Fuji 测试网 {@code 43113}（0xA869）</li>
 * </ul>
 *
 * <h2>状态机</h2>
 * <pre>
 *   正向跨链:  LOCK_PENDING ─► LOCKED ─► MINT_PENDING ─► MINTED
 *   反向跨链:  BURN_PENDING ─► BURNED ─► UNLOCK_PENDING ─► UNLOCKED
 *
 *   异常终态:  FAILED / CANCELLED / TIMEOUT
 * </pre>
 *
 * <h2>与 X/P Chain 的关系</h2>
 * <p>Avalanche 由 X-Chain（资产链）、P-Chain（平台链）、C-Chain（合约链）组成。
 * 本处理器仅在 C-Chain 上执行桥合约调用。若跨链涉及 X-Chain 原生 AVAX 资产或
 * P-Chain 子网验证者操作，需通过 AvalancheJS SDK 单独处理，详见
 * {@code docs/avalanche-cross-chain-guide.md}。</p>
 *
 * <h2>安全说明</h2>
 * <p>本处理器使用 Web3j 库进行交易签名和合约调用，
 * 私钥不落地存储，通过 nexus-core 的密钥管理服务签名。</p>
 *
 * @since 1.0.0
 */
public class AvalancheBridgeHandler extends AbstractBridgeHandler {

    private static final Logger log = LoggerFactory.getLogger(AvalancheBridgeHandler.class);

    /** Avalanche C-Chain 链 ID。 */
    private static final String CHAIN_ID = "avalanche";

    /** Avalanche C-Chain 推荐确认数（Snowman 共识最终性快，保守取 20）。 */
    private static final int RECOMMENDED_CONFIRMATIONS = 20;

    /** Avalanche C-Chain 桥合约地址。 */
    private String contractAddress;

    /** NEX 代币合约地址。 */
    private String nexusTokenAddress;

    /** RPC 端点 URL。 */
    private String rpcEndpoint;

    /** Web3j 客户端实例（懒加载）。 */
    private volatile Web3j web3j;

    /**
     * 构造 Avalanche C-Chain 桥处理器。
     *
     * @param config 桥配置
     */
    public AvalancheBridgeHandler(BridgeConfig config) {
        super(config);
    }

    /**
     * 构造 Avalanche C-Chain 桥处理器（带合约地址）。
     *
     * @param config            桥配置
     * @param contractAddress   桥合约地址
     * @param nexusTokenAddress  NEX 代币合约地址
     * @param rpcEndpoint       RPC 端点 URL
     */
    public AvalancheBridgeHandler(BridgeConfig config, String contractAddress,
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
        log.info("Submitting lock transaction on Avalanche C-Chain: amount={}, user={}",
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
        log.info("Submitting mint transaction on Avalanche C-Chain: lockTxId={}, minter={}",
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
        log.info("Submitting burn transaction on Avalanche C-Chain: amount={}, user={}",
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
        log.info("Submitting unlock transaction on Avalanche C-Chain: burnTxId={}, unlocker={}",
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
        log.debug("Querying transaction status on Avalanche C-Chain: txHash={}", txHash);
        ensureWeb3j();
        // C-Chain 出块约 2 秒，推荐确认数为 20
        return queryConfirmations(web3j, txHash);
    }

    /**
     * 等待交易达到 Avalanche C-Chain 推荐确认数（20）。
     *
     * <p>C-Chain 出块约 2 秒，Snowman 共识最终性快，
     * 轮询间隔 2 秒，超时 10 分钟。</p>
     *
     * @param txHash 交易哈希
     * @return 达到确认数返回 true；超时或失败返回 false
     */
    public boolean awaitConfirmations(String txHash) {
        ensureWeb3j();
        // C-Chain 出块约 2 秒，轮询 2 秒，超时 10 分钟
        return waitForConfirmations(web3j, txHash, RECOMMENDED_CONFIRMATIONS,
                2_000L, 10 * 60_000L);
    }

    /**
     * 获取 Avalanche C-Chain 推荐确认数。
     *
     * @return 推荐确认数
     */
    public int getRecommendedConfirmations() {
        return RECOMMENDED_CONFIRMATIONS;
    }

    /**
     * 懒加载 Web3j 客户端。
     *
     * <p>首次调用时根据 {@link #rpcEndpoint} 创建 Web3j 实例，
     * 后续调用直接复用。线程安全通过 volatile + 双检锁保证。</p>
     *
     * @throws BridgeException 如果 RPC 端点未配置
     */
    private void ensureWeb3j() throws BridgeException {
        if (web3j != null) {
            return;
        }
        if (rpcEndpoint == null || rpcEndpoint.isEmpty()) {
            throw new BridgeException("RPC_NOT_CONFIGURED",
                    "Avalanche C-Chain RPC endpoint is not configured");
        }
        synchronized (this) {
            if (web3j == null) {
                web3j = Web3j.build(new HttpService(rpcEndpoint));
                log.info("Initialized Web3j client for Avalanche C-Chain: {}", rpcEndpoint);
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
        // RPC 端点变更后重置 Web3j 客户端，下次调用时重新构建
        this.web3j = null;
    }
}