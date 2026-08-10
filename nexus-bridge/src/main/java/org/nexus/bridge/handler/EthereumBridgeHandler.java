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
 * 以太坊桥处理器，实现 Ethereum 网络上的跨链操作。
 *
 * <p>本处理器通过 Web3j 与以太坊节点通信，
 * 调用部署在以太坊上的 NexusChain 桥合约完成锁定、铸造、销毁、解锁操作。</p>
 *
 * <h2>合约交互</h2>
 * <ul>
 *   <li>桥合约地址由配置项 {@code nexus.bridge.ethereum.contract-address} 指定</li>
 *   <li>NEX 代币合约地址由配置项 {@code nexus.bridge.ethereum.nexus-token-address} 指定</li>
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
 * <h2>安全说明</h2>
 * <p>以太坊桥处理器使用 Web3j 库进行交易签名和合约调用，
 * 私钥不落地存储，通过 nexus-core 的密钥管理服务签名。</p>
 *
 * @since 1.0.0
 */
public class EthereumBridgeHandler extends AbstractBridgeHandler {

    private static final Logger log = LoggerFactory.getLogger(EthereumBridgeHandler.class);

    /** 以太坊链 ID。 */
    private static final String CHAIN_ID = "ethereum";

    /** 以太坊桥合约地址。 */
    private String contractAddress;

    /** NEX 代币合约地址。 */
    private String nexusTokenAddress;

    /** RPC 端点 URL。 */
    private String rpcEndpoint;

    /** Web3j 客户端实例（懒加载）。 */
    private volatile Web3j web3j;

    /**
     * 构造以太坊桥处理器。
     *
     * @param config 桥配置
     */
    public EthereumBridgeHandler(BridgeConfig config) {
        super(config);
    }

    /**
     * 构造以太坊桥处理器（带合约地址）。
     *
     * @param config            桥配置
     * @param contractAddress   桥合约地址
     * @param nexusTokenAddress  NEX 代币合约地址
     * @param rpcEndpoint       RPC 端点 URL
     */
    public EthereumBridgeHandler(BridgeConfig config, String contractAddress,
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
        log.info("Submitting lock transaction on Ethereum: amount={}, user={}",
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
        log.info("Submitting mint transaction on Ethereum: lockTxId={}, minter={}",
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
        log.info("Submitting burn transaction on Ethereum: amount={}, user={}",
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
        log.info("Submitting unlock transaction on Ethereum: burnTxId={}, unlocker={}",
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
        log.debug("Querying transaction status on Ethereum: txHash={}", txHash);
        ensureWeb3j();
        return queryConfirmations(web3j, txHash);
    }

    /**
     * 等待交易达到默认确认数（12）。
     *
     * @param txHash 交易哈希
     * @return 达到确认数返回 true；超时或失败返回 false
     */
    public boolean awaitConfirmations(String txHash) {
        ensureWeb3j();
        // 默认轮询 3 秒，超时 30 分钟
        return waitForConfirmations(web3j, txHash, DEFAULT_REQUIRED_CONFIRMATIONS,
                3_000L, 30 * 60_000L);
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
                    "Ethereum RPC endpoint is not configured");
        }
        synchronized (this) {
            if (web3j == null) {
                web3j = Web3j.build(new HttpService(rpcEndpoint));
                log.info("Initialized Web3j client for Ethereum: {}", rpcEndpoint);
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
