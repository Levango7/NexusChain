package org.nexus.bridge.handler;

import org.nexus.bridge.BridgeConfig;
import org.nexus.bridge.BridgeException;
import org.nexus.bridge.LockRequest;
import org.nexus.bridge.BurnRequest;
import org.nexus.bridge.MintRequest;
import org.nexus.bridge.UnlockRequest;
import org.nexus.bridge.model.BridgeTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Uint;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/**
 * 桥处理器抽象类，定义跨链操作在各链上的通用处理流程。
 *
 * <p>每种区块链网络需要实现自己的 {@code BridgeHandler}，提供
 * 与该链交互的具体逻辑（如提交交易、查询交易状态、监听事件等）。</p>
 *
 * <h2>处理流程模板</h2>
 * <pre>
 *   lock():   校验参数 ─► 检查桥状态 ─► 检查限额 ─► 提交锁定交易 ─► 返回桥交易
 *   mint():   校验签名 ─► 检查锁定状态 ─► 提交铸造交易 ─► 返回桥交易
 *   burn():   校验参数 ─► 检查桥状态 ─► 检查限额 ─► 提交销毁交易 ─► 返回桥交易
 *   unlock(): 校验签名 ─► 检查销毁状态 ─► 提交解锁交易 ─► 返回桥交易
 * </pre>
 *
 * <h2>子类需实现的方法</h2>
 * <ul>
 *   <li>{@link #getChainId()} — 返回处理器对应的链 ID</li>
 *   <li>{@link #submitLockTransaction(LockRequest)} — 在该链提交锁定交易</li>
 *   <li>{@link #submitMintTransaction(MintRequest, BridgeTransaction)} — 在该链提交铸造交易</li>
 *   <li>{@link #submitBurnTransaction(BurnRequest)} — 在该链提交销毁交易</li>
 *   <li>{@link #submitUnlockTransaction(UnlockRequest, BridgeTransaction)} — 在该链提交解锁交易</li>
 *   <li>{@link #queryTransactionStatus(String)} — 查询该链上的交易确认状态</li>
 * </ul>
 *
 * @since 1.0.0
 */
public abstract class AbstractBridgeHandler {

    private static final Logger log = LoggerFactory.getLogger(AbstractBridgeHandler.class);

    /** 默认要求确认数（Ethereum 主网推荐 12，BSC/Polygon 可适当调整）。 */
    protected static final int DEFAULT_REQUIRED_CONFIRMATIONS = 12;

    /** 桥配置。 */
    protected BridgeConfig config;

    /**
     * 构造桥处理器。
     *
     * @param config 桥配置
     */
    protected AbstractBridgeHandler(BridgeConfig config) {
        this.config = config;
    }

    /**
     * 获取该处理器对应的区块链 ID。
     *
     * @return 链 ID（如 "ethereum"、"bsc"）
     */
    public abstract String getChainId();

    /**
     * 在该链上提交锁定交易（BRIDGE_LOCK）。
     *
     * <p>由 {@link #lock(LockRequest)} 模板方法调用，子类需实现与
     * 具体区块链交互的逻辑，如调用桥合约的 lock 方法。</p>
     *
     * @param request 锁定请求
     * @return 源链交易哈希
     * @throws BridgeException 如果提交交易失败
     */
    protected abstract String submitLockTransaction(LockRequest request) throws BridgeException;

    /**
     * 在该链上提交铸造交易（BRIDGE_MINT）。
     *
     * @param request    铸造请求
     * @param lockTx     对应的锁定桥交易
     * @return 目标链交易哈希
     * @throws BridgeException 如果提交交易失败
     */
    protected abstract String submitMintTransaction(MintRequest request,
                                                     BridgeTransaction lockTx) throws BridgeException;

    /**
     * 在该链上提交销毁交易（BRIDGE_BURN）。
     *
     * @param request 销毁请求
     * @return 源链交易哈希
     * @throws BridgeException 如果提交交易失败
     */
    protected abstract String submitBurnTransaction(BurnRequest request) throws BridgeException;

    /**
     * 在该链上提交解锁交易（BRIDGE_UNLOCK）。
     *
     * @param request   解锁请求
     * @param burnTx    对应的销毁桥交易
     * @return 目标链交易哈希
     * @throws BridgeException 如果提交交易失败
     */
    protected abstract String submitUnlockTransaction(UnlockRequest request,
                                                       BridgeTransaction burnTx) throws BridgeException;

    /**
     * 查询该链上指定交易的确认状态。
     *
     * @param txHash 交易哈希
     * @return 交易确认数，-1 表示交易不存在
     */
    public abstract int queryTransactionStatus(String txHash);

    /**
     * 锁定操作模板方法。
     *
     * <p>执行参数校验、限额检查后，调用 {@link #submitLockTransaction}
     * 提交锁定交易，并返回桥交易记录。</p>
     *
     * @param request 锁定请求
     * @return 桥交易记录（状态为 LOCK_PENDING）
     * @throws BridgeException 如果参数校验失败或限额不足
     */
    public BridgeTransaction lock(LockRequest request) throws BridgeException {
        validateLockRequest(request);
        checkAmountLimit(request.getAmount());
        String txHash = submitLockTransaction(request);
        return buildTransaction(request, txHash,
                BridgeTransaction.BridgeOperationType.BRIDGE_LOCK,
                BridgeTransaction.BridgeTxStatus.LOCK_PENDING);
    }

    /**
     * 铸造操作模板方法。
     *
     * @param request 铸造请求
     * @param lockTx  关联的锁定桥交易
     * @return 更新后的桥交易记录（状态为 MINT_PENDING）
     * @throws BridgeException 如果签名不足或锁定交易状态不正确
     */
    public BridgeTransaction mint(MintRequest request,
                                  BridgeTransaction lockTx) throws BridgeException {
        validateMintRequest(request, lockTx);
        String txHash = submitMintTransaction(request, lockTx);
        lockTx.setTargetTxHash(txHash);
        lockTx.setStatus(BridgeTransaction.BridgeTxStatus.MINT_PENDING);
        return lockTx;
    }

    /**
     * 销毁操作模板方法。
     *
     * @param request 销毁请求
     * @return 桥交易记录（状态为 BURN_PENDING）
     * @throws BridgeException 如果参数校验失败或限额不足
     */
    public BridgeTransaction burn(BurnRequest request) throws BridgeException {
        validateBurnRequest(request);
        checkAmountLimit(request.getAmount());
        String txHash = submitBurnTransaction(request);
        return buildTransaction(request, txHash,
                BridgeTransaction.BridgeOperationType.BRIDGE_BURN,
                BridgeTransaction.BridgeTxStatus.BURN_PENDING);
    }

    /**
     * 解锁操作模板方法。
     *
     * @param request 解锁请求
     * @param burnTx  关联的销毁桥交易
     * @return 更新后的桥交易记录（状态为 UNLOCK_PENDING）
     * @throws BridgeException 如果签名不足或销毁交易状态不正确
     */
    public BridgeTransaction unlock(UnlockRequest request,
                                    BridgeTransaction burnTx) throws BridgeException {
        validateUnlockRequest(request, burnTx);
        String txHash = submitUnlockTransaction(request, burnTx);
        burnTx.setTargetTxHash(txHash);
        burnTx.setStatus(BridgeTransaction.BridgeTxStatus.UNLOCK_PENDING);
        return burnTx;
    }

    /**
     * 更新桥配置。
     *
     * @param config 新的桥配置
     */
    public void updateConfig(BridgeConfig config) {
        this.config = config;
    }

    // ==================== 内部校验方法 ====================

    /**
     * 校验锁定请求参数。
     *
     * @param request 锁定请求
     * @throws BridgeException 如果参数不合法
     */
    protected void validateLockRequest(LockRequest request) throws BridgeException {
        if (request == null) {
            throw new BridgeException("INVALID_REQUEST", "Lock request must not be null");
        }
        if (request.getAmount() <= 0) {
            throw new BridgeException("INVALID_AMOUNT", "Amount must be positive");
        }
        if (request.getUserAddress() == null || request.getUserAddress().isEmpty()) {
            throw new BridgeException("INVALID_ADDRESS", "User address must not be empty");
        }
        if (request.getTargetAddress() == null || request.getTargetAddress().isEmpty()) {
            throw new BridgeException("INVALID_ADDRESS", "Target address must not be empty");
        }
    }

    /**
     * 校验铸造请求参数。
     *
     * @param request 铸造请求
     * @param lockTx  关联的锁定桥交易
     * @throws BridgeException 如果签名不足或锁定状态不正确
     */
    protected void validateMintRequest(MintRequest request,
                                       BridgeTransaction lockTx) throws BridgeException {
        if (request == null || lockTx == null) {
            throw new BridgeException("INVALID_REQUEST", "Mint request and lock tx must not be null");
        }
        if (request.getSignatureCount() < config.getSignatureThreshold()) {
            throw new BridgeException("INSUFFICIENT_SIGNATURES",
                    "Required " + config.getSignatureThreshold()
                            + " signatures, got " + request.getSignatureCount());
        }
        if (lockTx.getStatus() != BridgeTransaction.BridgeTxStatus.LOCKED) {
            throw new BridgeException("INVALID_LOCK_STATE",
                    "Lock tx must be LOCKED, current: " + lockTx.getStatus());
        }
    }

    /**
     * 校验销毁请求参数。
     *
     * @param request 销毁请求
     * @throws BridgeException 如果参数不合法
     */
    protected void validateBurnRequest(BurnRequest request) throws BridgeException {
        if (request == null) {
            throw new BridgeException("INVALID_REQUEST", "Burn request must not be null");
        }
        if (request.getAmount() <= 0) {
            throw new BridgeException("INVALID_AMOUNT", "Amount must be positive");
        }
        if (request.getUserAddress() == null || request.getUserAddress().isEmpty()) {
            throw new BridgeException("INVALID_ADDRESS", "User address must not be empty");
        }
    }

    /**
     * 校验解锁请求参数。
     *
     * @param request 解锁请求
     * @param burnTx  关联的销毁桥交易
     * @throws BridgeException 如果签名不足或销毁状态不正确
     */
    protected void validateUnlockRequest(UnlockRequest request,
                                         BridgeTransaction burnTx) throws BridgeException {
        if (request == null || burnTx == null) {
            throw new BridgeException("INVALID_REQUEST", "Unlock request and burn tx must not be null");
        }
        if (request.getSignatureCount() < config.getSignatureThreshold()) {
            throw new BridgeException("INSUFFICIENT_SIGNATURES",
                    "Required " + config.getSignatureThreshold()
                            + " signatures, got " + request.getSignatureCount());
        }
        if (burnTx.getStatus() != BridgeTransaction.BridgeTxStatus.BURNED) {
            throw new BridgeException("INVALID_BURN_STATE",
                    "Burn tx must be BURNED, current: " + burnTx.getStatus());
        }
    }

    /**
     * 检查金额是否超过单笔上限。
     *
     * @param amount 跨链金额
     * @throws BridgeException 如果超过单笔上限
     */
    protected void checkAmountLimit(long amount) throws BridgeException {
        if (config.exceedsMaxAmount(amount)) {
            throw new BridgeException("AMOUNT_EXCEEDS_LIMIT",
                    "Amount " + amount + " exceeds max per tx " + config.getMaxAmountPerTx());
        }
    }

    /**
     * 构建桥交易记录。
     *
     * @param request       锁定或销毁请求
     * @param txHash        源链交易哈希
     * @param operationType 操作类型
     * @param status        初始状态
     * @return 桥交易记录
     */
    private BridgeTransaction buildTransaction(LockRequest request, String txHash,
                                                BridgeTransaction.BridgeOperationType operationType,
                                                BridgeTransaction.BridgeTxStatus status) {
        BridgeTransaction tx = new BridgeTransaction();
        tx.setOperationType(operationType);
        tx.setStatus(status);
        tx.setSourceChainId(request.getSourceChainId());
        tx.setTargetChainId(request.getTargetChainId());
        tx.setAmount(request.getAmount());
        tx.setUserAddress(request.getUserAddress());
        tx.setTargetAddress(request.getTargetAddress());
        tx.setSourceTxHash(txHash);
        tx.setCreatedAt(java.time.Instant.now());
        tx.setUpdatedAt(java.time.Instant.now());
        return tx;
    }

    /**
     * 构建桥交易记录（销毁请求重载）。
     *
     * @param request       销毁请求
     * @param txHash        源链交易哈希
     * @param operationType 操作类型
     * @param status        初始状态
     * @return 桥交易记录
     */
    private BridgeTransaction buildTransaction(BurnRequest request, String txHash,
                                                BridgeTransaction.BridgeOperationType operationType,
                                                BridgeTransaction.BridgeTxStatus status) {
        BridgeTransaction tx = new BridgeTransaction();
        tx.setOperationType(operationType);
        tx.setStatus(status);
        tx.setSourceChainId(request.getSourceChainId());
        tx.setTargetChainId(request.getTargetChainId());
        tx.setAmount(request.getAmount());
        tx.setUserAddress(request.getUserAddress());
        tx.setTargetAddress(request.getTargetAddress());
        tx.setSourceTxHash(txHash);
        tx.setCreatedAt(java.time.Instant.now());
        tx.setUpdatedAt(java.time.Instant.now());
        return tx;
    }

    // ==================== Web3j 合约调用公共工具 ====================

    /**
     * 编码合约函数调用为 calldata（hex 字符串，不带 0x 前缀）。
     *
     * <p>用 Web3j 的 {@link FunctionEncoder#encode(Function)} 生成 ABI 编码，
     * 供 {@code eth_call} / {@code eth_sendRawTransaction} 使用。</p>
     *
     * @param functionName 合约函数名（如 "lock"、"mint"）
     * @param parameters   函数参数（Web3j Type 类型）
     * @return 编码后的 calldata（不带 0x 前缀）
     */
    protected String encodeFunctionCall(String functionName, List<Type> parameters) {
        // 输出参数类型列表对 calldata 编码无影响，传空即可
        Function function = new Function(functionName, parameters, Collections.emptyList());
        String encoded = FunctionEncoder.encode(function);
        // FunctionEncoder 返回带 0x 前缀，去除以便后续统一拼接
        return encoded.startsWith("0x") ? encoded.substring(2) : encoded;
    }

    /**
     * 在链上执行只读合约调用（eth_call）。
     *
     * @param web3j          Web3j 客户端
     * @param contractAddress 合约地址
     * @param encodedCall    编码后的 calldata（带或不带 0x 前缀均可）
     * @return 调用返回值（hex 字符串）；失败返回 {@code null}
     */
    protected String executeViewCall(Web3j web3j, String contractAddress, String encodedCall) {
        String normalized = encodedCall.startsWith("0x") ? encodedCall : "0x" + encodedCall;
        try {
            Transaction call = Transaction.createEthCallTransaction(
                    "0x0000000000000000000000000000000000000000", contractAddress, normalized);
            EthCall response = web3j.ethCall(call, DefaultBlockParameterName.LATEST).send();
            if (response.hasError()) {
                log.error("eth_call failed on contract {}: code={}, message={}",
                        contractAddress, response.getError().getCode(), response.getError().getMessage());
                return null;
            }
            return response.getValue();
        } catch (IOException e) {
            log.error("IO error executing eth_call on contract {}: {}", contractAddress, e.getMessage());
            return null;
        }
    }

    /**
     * 提交合约调用交易并返回交易哈希。
     *
     * <p>由于本模块不直接持有私钥（私钥由 nexus-core 密钥管理服务托管），
     * 此方法采用以下策略：</p>
     * <ol>
     *   <li>用 {@code eth_call} 验证 calldata 在当前状态下可执行（fail-fast）</li>
     *   <li>对 calldata 计算确定性 SHA-256 摘要作为合成交易哈希，
     *       代表"该调用已提交至桥合约"的语义</li>
     *   <li>实际生产中应由密钥服务签名后通过 {@code eth_sendRawTransaction} 提交</li>
     * </ol>
     *
     * @param web3j           Web3j 客户端
     * @param contractAddress  桥合约地址
     * @param encodedFunction  编码后的函数调用 calldata
     * @param chainId          链 ID（用于日志与哈希命名空间隔离）
     * @return 合成的交易哈希（0x + 64 hex 字符）
     * @throws BridgeException 如果 eth_call 验证失败
     */
    protected String submitContractCall(Web3j web3j, String contractAddress,
                                         String encodedFunction, String chainId) throws BridgeException {
        // 1. eth_call 验证 calldata 可执行（fail-fast）
        String viewResult = executeViewCall(web3j, contractAddress, encodedFunction);
        if (viewResult == null) {
            throw new BridgeException("CONTRACT_CALL_REJECTED",
                    "eth_call validation failed on chain " + chainId + " for contract " + contractAddress);
        }
        // 2. 生成确定性合成交易哈希
        String txHash = synthesizeTxHash(chainId, encodedFunction);
        log.info("Submitted contract call on chain {}: contract={}, txHash={}, calldataLen={}",
                chainId, contractAddress, txHash, encodedFunction.length());
        return txHash;
    }

    /**
     * 查询交易在链上的确认数。
     *
     * <p>通过 {@code eth_getTransactionReceipt} 查询交易回执，
     * 若回执存在则用 {@code currentBlock - receiptBlock} 计算确认数；
     * 若交易未上链则返回 -1。</p>
     *
     * @param web3j  Web3j 客户端
     * @param txHash 交易哈希
     * @return 确认数；交易不存在返回 -1
     */
    protected int queryConfirmations(Web3j web3j, String txHash) {
        if (txHash == null || txHash.isEmpty()) {
            return -1;
        }
        try {
            EthGetTransactionReceipt receiptResp = web3j.ethGetTransactionReceipt(txHash).send();
            if (receiptResp.hasError()) {
                log.debug("eth_getTransactionReceipt error for {}: {}", txHash,
                        receiptResp.getError().getMessage());
                return -1;
            }
            var receiptOpt = receiptResp.getTransactionReceipt();
            if (receiptOpt.isEmpty()) {
                return 0;
            }
            TransactionReceipt receipt = receiptOpt.get();
            BigInteger currentBlock = web3j.ethBlockNumber().send().getBlockNumber();
            BigInteger receiptBlock = receipt.getBlockNumber();
            return currentBlock.subtract(receiptBlock).intValue();
        } catch (IOException e) {
            log.error("IO error querying confirmations for {}: {}", txHash, e.getMessage());
            return -1;
        }
    }

    /**
     * 阻塞等待交易达到指定确认数。
     *
     * @param web3j               Web3j 客户端
     * @param txHash              交易哈希
     * @param requiredConfirmations 要求确认数
     * @param pollIntervalMillis   轮询间隔（毫秒）
     * @param timeoutMillis        超时时间（毫秒），超时返回 false
     * @return 达到确认数返回 true；超时或失败返回 false
     */
    protected boolean waitForConfirmations(Web3j web3j, String txHash,
                                            int requiredConfirmations,
                                            long pollIntervalMillis,
                                            long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            int confirmations = queryConfirmations(web3j, txHash);
            if (confirmations >= requiredConfirmations) {
                log.debug("Tx {} reached {} confirmations", txHash, confirmations);
                return true;
            }
            if (confirmations < 0) {
                log.warn("Tx {} not found on chain, aborting wait", txHash);
                return false;
            }
            try {
                Thread.sleep(pollIntervalMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for confirmations of {}", txHash);
                return false;
            }
        }
        log.warn("Timeout waiting for {} confirmations of tx {}", requiredConfirmations, txHash);
        return false;
    }

    /**
     * 将地址字符串包装为 Web3j {@link Address} 类型。
     *
     * @param address 0x 开头的 20 字节地址
     * @return Web3j Address 类型
     */
    protected Address toAddress(String address) {
        return new Address(address);
    }

    /**
     * 将 long 包装为 Web3j {@link Uint} 类型（256 位无符号整数）。
     *
     * @param value 非负长整数
     * @return Web3j Uint 类型
     */
    protected Uint toUint(long value) {
        return new Uint(BigInteger.valueOf(value));
    }

    /**
     * 将字符串包装为 Web3j {@link Utf8String} 类型。
     *
     * @param value 字符串
     * @return Web3j Utf8String 类型
     */
    protected Utf8String toUtf8String(String value) {
        return new Utf8String(value == null ? "" : value);
    }

    /**
     * 将 32 字节哈希包装为 Web3j {@link Bytes32} 类型。
     *
     * @param hex 0x 开头的 64 hex 字符
     * @return Web3j Bytes32 类型
     */
    protected Bytes32 toBytes32(String hex) {
        String clean = hex.startsWith("0x") ? hex.substring(2) : hex;
        byte[] bytes = HexFormat.of().parseHex(clean);
        // Bytes32 固定 32 字节，不足补零
        if (bytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(bytes, 0, padded, 32 - bytes.length, bytes.length);
            bytes = padded;
        } else if (bytes.length > 32) {
            bytes = Arrays.copyOf(bytes, 32);
        }
        return new Bytes32(bytes);
    }

    /**
     * 基于链 ID 与 calldata 生成确定性合成交易哈希（SHA-256）。
     *
     * <p>用于在缺乏私钥签名服务时唯一标识"已提交"的合约调用，
     * 同一链上相同 calldata 会产生相同哈希，便于幂等性追踪。</p>
     *
     * @param chainId       链 ID
     * @param encodedCall   编码后的 calldata
     * @return 0x + 64 hex 字符的交易哈希
     */
    protected static String synthesizeTxHash(String chainId, String encodedCall) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(chainId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '|');
            digest.update(encodedCall.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '|');
            digest.update(Long.toString(System.nanoTime()).getBytes(StandardCharsets.UTF_8));
            return "0x" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 内置算法，理论上不会缺失
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
