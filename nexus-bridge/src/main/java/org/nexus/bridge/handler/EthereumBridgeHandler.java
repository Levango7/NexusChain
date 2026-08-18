package org.nexus.bridge.handler;

import org.nexus.bridge.BridgeConfig;
import org.nexus.bridge.BurnRequest;
import org.nexus.bridge.BridgeException;
import org.nexus.bridge.LockRequest;
import org.nexus.bridge.MintRequest;
import org.nexus.bridge.UnlockRequest;
import org.nexus.bridge.keyvault.KeyVault;
import org.nexus.bridge.model.BridgeTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

/**
 * 以太坊桥处理器，实现 Ethereum 网络上的跨链操作。
 *
 * <p>本处理器通过 Web3j 与以太坊节点通信，
 * 调用部署在以太坊上的 NexusChain 桥合约（BridgeSource / BridgeTarget）完成
 * 锁定、铸造、销毁、解锁操作。</p>
 *
 * <h2>合约交互</h2>
 * <ul>
 *   <li>源链桥合约（BridgeSource）地址由 {@link #contractAddress} 指定，用于 lock/unlock</li>
 *   <li>目标链桥合约（BridgeTarget）地址由 {@link #targetContractAddress} 指定，用于 mint/burn；
 *       若未单独配置，回退到 {@link #contractAddress}</li>
 *   <li>NEX 代币合约地址由 {@link #nexusTokenAddress} 指定，作为 lock/burn 的 token 参数</li>
 *   <li>铸币权限需在桥合约上设置给 relayer 多签地址</li>
 * </ul>
 *
 * <h2>合约函数签名（对齐 BridgeSource.sol / BridgeTarget.sol）</h2>
 * <pre>
 *   // BridgeSource.sol
 *   lock(address token, address recipient, uint256 amount, uint256 targetChainId, bytes32 nonce)
 *   unlock(address token, address recipient, uint256 amount, bytes32 nonce, bytes signature)
 *
 *   // BridgeTarget.sol
 *   mint(address token, address recipient, uint256 amount, bytes32 nonce, bytes signature)
 *   burn(address token, uint256 amount, bytes32 nonce, address targetRecipient)
 * </pre>
 *
 * <h2>真实交易发送</h2>
 * <p>当 {@link #credentials} 已配置时，lock/mint/burn/unlock 通过
 * {@code eth_sendRawTransaction} 提交真实上链交易并返回真实交易哈希。
 * 否则回退到合成哈希模式（仅 eth_call 验证 + SHA-256 摘要），用于测试环境。</p>
 *
 * <h2>安全说明</h2>
 * <p>以太坊桥处理器使用 Web3j 库进行交易签名和合约调用，
 * 私钥不落地存储，通过 {@link KeyVault} 签名服务签名跨链消息参数，
 * 通过 {@link Credentials} 签名 EVM 交易本身。</p>
 *
 * @since 1.0.0
 */
public class EthereumBridgeHandler extends AbstractBridgeHandler {

    private static final Logger log = LoggerFactory.getLogger(EthereumBridgeHandler.class);

    /** 以太坊链 ID（字符串标识）。 */
    private static final String CHAIN_ID = "ethereum";

    /** 以太坊主网 EVM 链 ID（数值，用于 EIP-155 签名）。 */
    private static final long ETHEREUM_MAINNET_CHAIN_ID = 1L;

    /** 源链桥合约地址（BridgeSource.sol），用于 lock/unlock。 */
    private String contractAddress;

    /** 目标链桥合约地址（BridgeTarget.sol），用于 mint/burn；null 时回退到 contractAddress。 */
    private String targetContractAddress;

    /** NEX 代币合约地址（作为 lock/burn 的 token 参数）。 */
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
     * @param contractAddress   源链桥合约地址（BridgeSource）
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

    /**
     * 构造以太坊桥处理器（全参数，支持真实交易发送）。
     *
     * @param config              桥配置
     * @param sourceContractAddress  源链桥合约地址（BridgeSource）
     * @param targetContractAddress  目标链桥合约地址（BridgeTarget）
     * @param nexusTokenAddress    NEX 代币合约地址
     * @param rpcEndpoint         RPC 端点 URL
     * @param credentials         Web3j 凭证（私钥），用于签署真实 EVM 交易；null 表示使用合成哈希模式
     * @param evmChainId          EVM 链 ID（数值，用于 EIP-155 签名），null 表示使用 Ethereum 主网 (1)
     * @param keyVault            签名服务（可选），用于签名跨链消息参数
     * @param relayerValidatorId  relayer 在 KeyVault 中的 validator ID
     */
    public EthereumBridgeHandler(BridgeConfig config, String sourceContractAddress,
                                  String targetContractAddress, String nexusTokenAddress,
                                  String rpcEndpoint, Credentials credentials,
                                  Long evmChainId, KeyVault keyVault, String relayerValidatorId) {
        super(config);
        this.contractAddress = sourceContractAddress;
        this.targetContractAddress = targetContractAddress;
        this.nexusTokenAddress = nexusTokenAddress;
        this.rpcEndpoint = rpcEndpoint;
        this.credentials = credentials;
        this.evmChainId = evmChainId != null ? BigInteger.valueOf(evmChainId) : BigInteger.valueOf(ETHEREUM_MAINNET_CHAIN_ID);
        this.keyVault = keyVault;
        this.relayerValidatorId = relayerValidatorId;
    }

    @Override
    public String getChainId() {
        return CHAIN_ID;
    }

    /**
     * 解析目标链桥合约地址：优先使用 {@link #targetContractAddress}，否则回退到 {@link #contractAddress}。
     *
     * @return 目标链桥合约地址
     */
    private String resolveTargetContract() {
        return (targetContractAddress != null && !targetContractAddress.isEmpty())
                ? targetContractAddress : contractAddress;
    }

    /**
     * 将字符串链 ID（如 "bsc"、"ethereum"）解析为数值。
     *
     * @param chainIdStr 链 ID 字符串
     * @return 数值链 ID；未知链返回 0
     */
    private long resolveChainIdNumeric(String chainIdStr) {
        if (chainIdStr == null) {
            return 0L;
        }
        switch (chainIdStr.toLowerCase()) {
            case "ethereum": return 1L;
            case "bsc": return 56L;
            case "polygon": return 137L;
            case "avalanche": return 43114L;
            case "arbitrum": return 42161L;
            case "optimism": return 10L;
            default:
                try {
                    return Long.parseLong(chainIdStr);
                } catch (NumberFormatException e) {
                    return 0L;
                }
        }
    }

    @Override
    protected String submitLockTransaction(LockRequest request) throws BridgeException {
        log.info("Submitting lock transaction on Ethereum: amount={}, user={}, token={}",
                request.getAmount(), request.getUserAddress(), nexusTokenAddress);
        ensureWeb3j();
        // 对齐 BridgeSource.sol: lock(address token, address recipient, uint256 amount, uint256 targetChainId, bytes32 nonce)
        String nonce = generateBridgeNonce(
                request.getSourceTxHash(), request.getUserAddress(), request.getAmount());
        List<org.web3j.abi.datatypes.Type> params = Arrays.asList(
                toAddress(nexusTokenAddress),                   // token
                toAddress(request.getTargetAddress()),          // recipient
                toUint(request.getAmount()),                    // amount
                toUint(resolveChainIdNumeric(request.getTargetChainId())),  // targetChainId
                toBytes32(nonce)                                // nonce
        );
        String encoded = encodeFunctionCall("lock", params);
        return submitContractCall(web3j, contractAddress, encoded, CHAIN_ID);
    }

    @Override
    protected String submitMintTransaction(MintRequest request,
                                            BridgeTransaction lockTx) throws BridgeException {
        log.info("Submitting mint transaction on Ethereum: lockTxId={}, minter={}, token={}",
                request.getLockTxId(), request.getMinterAddress(), nexusTokenAddress);
        ensureWeb3j();
        // 对齐 BridgeTarget.sol: mint(address token, address recipient, uint256 amount, bytes32 nonce, bytes signature)
        // nonce 使用 lockTxId（锁定交易哈希）作为幂等键
        String signature = resolveRelayerSignature(request.getSignatures(), request.getMinterAddress());
        List<org.web3j.abi.datatypes.Type> params = Arrays.asList(
                toAddress(nexusTokenAddress),                   // token
                toAddress(lockTx.getTargetAddress()),           // recipient
                toUint(lockTx.getAmount()),                     // amount
                toBytes32(request.getLockTxId()),               // nonce
                toBytes(signature)                              // signature (动态 bytes)
        );
        String encoded = encodeFunctionCall("mint", params);
        return submitContractCall(web3j, resolveTargetContract(), encoded, CHAIN_ID);
    }

    @Override
    protected String submitBurnTransaction(BurnRequest request) throws BridgeException {
        log.info("Submitting burn transaction on Ethereum: amount={}, user={}, token={}",
                request.getAmount(), request.getUserAddress(), nexusTokenAddress);
        ensureWeb3j();
        // 对齐 BridgeTarget.sol: burn(address token, uint256 amount, bytes32 nonce, address targetRecipient)
        String nonce = generateBridgeNonce(
                request.getSourceTxHash(), request.getUserAddress(), request.getAmount());
        List<org.web3j.abi.datatypes.Type> params = Arrays.asList(
                toAddress(nexusTokenAddress),                   // token
                toUint(request.getAmount()),                    // amount
                toBytes32(nonce),                               // nonce
                toAddress(request.getTargetAddress())           // targetRecipient
        );
        String encoded = encodeFunctionCall("burn", params);
        return submitContractCall(web3j, resolveTargetContract(), encoded, CHAIN_ID);
    }

    @Override
    protected String submitUnlockTransaction(UnlockRequest request,
                                              BridgeTransaction burnTx) throws BridgeException {
        log.info("Submitting unlock transaction on Ethereum: burnTxId={}, unlocker={}, token={}",
                request.getBurnTxId(), request.getUnlockerAddress(), nexusTokenAddress);
        ensureWeb3j();
        // 对齐 BridgeSource.sol: unlock(address token, address recipient, uint256 amount, bytes32 nonce, bytes signature)
        // nonce 使用 burnTxId（销毁交易哈希）作为幂等键
        String signature = resolveRelayerSignature(request.getSignatures(), request.getUnlockerAddress());
        List<org.web3j.abi.datatypes.Type> params = Arrays.asList(
                toAddress(nexusTokenAddress),                   // token
                toAddress(burnTx.getTargetAddress()),           // recipient
                toUint(burnTx.getAmount()),                     // amount
                toBytes32(request.getBurnTxId()),               // nonce
                toBytes(signature)                              // signature (动态 bytes)
        );
        String encoded = encodeFunctionCall("unlock", params);
        return submitContractCall(web3j, contractAddress, encoded, CHAIN_ID);
    }

    /**
     * 解析 relayer 签名：优先从签名集合中取出对应 relayer 的签名，
     * 若不存在则尝试通过 KeyVault 签名服务签名。
     *
     * @param signatures    验证者签名集合（验证者 ID → 签名）
     * @param relayerAddress relayer 地址
     * @return 签名 hex 字符串；若无可用签名返回空字符串
     */
    private String resolveRelayerSignature(java.util.Map<String, String> signatures, String relayerAddress) {
        // 1. 优先使用请求中携带的签名
        if (signatures != null && !signatures.isEmpty()) {
            // 优先按 relayerAddress 查找，否则取第一个可用签名
            String sig = signatures.get(relayerAddress);
            if (sig != null) {
                return sig;
            }
            // 取任意一个签名（多签场景下合约会逐一验证）
            return signatures.values().iterator().next();
        }
        // 2. 尝试通过 KeyVault 签名服务签名
        if (keyVault != null && relayerValidatorId != null && relayerAddress != null) {
            byte[] payload = relayerAddress.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String signed = signBridgeMessage(payload);
            if (signed != null) {
                return signed;
            }
        }
        // 3. 无可用签名，返回空（合约侧会 revert）
        log.warn("No relayer signature available for {}", relayerAddress);
        return "";
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

    /**
     * 获取目标链桥合约地址（BridgeTarget）。
     *
     * @return 目标链桥合约地址；若未配置返回 null
     */
    public String getTargetContractAddress() {
        return targetContractAddress;
    }

    /**
     * 设置目标链桥合约地址（BridgeTarget），用于 mint/burn。
     *
     * @param targetContractAddress 目标链桥合约地址
     */
    public void setTargetContractAddress(String targetContractAddress) {
        this.targetContractAddress = targetContractAddress;
    }

    public String getCpayTokenAddress() {
        return nexusTokenAddress;
    }

    public void setCpayTokenAddress(String nexusTokenAddress) {
        this.nexusTokenAddress = nexusTokenAddress;
    }

    /**
     * 获取 NEX 代币合约地址。
     *
     * @return NEX 代币合约地址
     */
    public String getNexusTokenAddress() {
        return nexusTokenAddress;
    }

    /**
     * 设置 NEX 代币合约地址。
     *
     * @param nexusTokenAddress NEX 代币合约地址
     */
    public void setNexusTokenAddress(String nexusTokenAddress) {
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

    /**
     * 获取 Web3j 凭证（私钥）。
     *
     * @return 凭证；未配置返回 null
     */
    public Credentials getCredentials() {
        return credentials;
    }

    /**
     * 设置 Web3j 凭证（私钥），启用真实交易发送模式。
     *
     * @param credentials Web3j 凭证
     */
    public void setCredentials(Credentials credentials) {
        this.credentials = credentials;
    }

    /**
     * 获取 EVM 链 ID（数值，用于 EIP-155 签名）。
     *
     * @return EVM 链 ID；未配置返回 null
     */
    public BigInteger getEvmChainId() {
        return evmChainId;
    }

    /**
     * 设置 EVM 链 ID（数值，用于 EIP-155 签名）。
     *
     * @param evmChainId EVM 链 ID
     */
    public void setEvmChainId(BigInteger evmChainId) {
        this.evmChainId = evmChainId;
    }

    /**
     * 获取签名服务（KeyVault）。
     *
     * @return KeyVault；未配置返回 null
     */
    public KeyVault getKeyVault() {
        return keyVault;
    }

    /**
     * 设置签名服务（KeyVault），用于签名跨链消息参数。
     *
     * @param keyVault KeyVault 实例
     */
    public void setKeyVault(KeyVault keyVault) {
        this.keyVault = keyVault;
    }

    /**
     * 获取 relayer 在 KeyVault 中的 validator ID。
     *
     * @return validator ID；未配置返回 null
     */
    public String getRelayerValidatorId() {
        return relayerValidatorId;
    }

    /**
     * 设置 relayer 在 KeyVault 中的 validator ID。
     *
     * @param relayerValidatorId validator ID
     */
    public void setRelayerValidatorId(String relayerValidatorId) {
        this.relayerValidatorId = relayerValidatorId;
    }

    /**
     * 获取固定 gas price（wei）。
     *
     * @return gas price；未配置返回 null（表示使用网络建议值）
     */
    public BigInteger getGasPrice() {
        return gasPrice;
    }

    /**
     * 设置固定 gas price（wei）。null 表示使用 eth_gasPrice 网络建议值。
     *
     * @param gasPrice gas price
     */
    public void setGasPrice(BigInteger gasPrice) {
        this.gasPrice = gasPrice;
    }

    /**
     * 获取固定 gas limit。
     *
     * @return gas limit；未配置返回 null（使用默认值）
     */
    public BigInteger getGasLimit() {
        return gasLimit;
    }

    /**
     * 设置固定 gas limit。null 表示使用默认值（500000）。
     *
     * @param gasLimit gas limit
     */
    public void setGasLimit(BigInteger gasLimit) {
        this.gasLimit = gasLimit;
    }
}
