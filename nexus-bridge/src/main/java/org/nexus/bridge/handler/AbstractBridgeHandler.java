package org.nexus.bridge.handler;

import org.nexus.bridge.BridgeConfig;
import org.nexus.bridge.BridgeException;
import org.nexus.bridge.LockRequest;
import org.nexus.bridge.BurnRequest;
import org.nexus.bridge.MintRequest;
import org.nexus.bridge.UnlockRequest;
import org.nexus.bridge.model.BridgeTransaction;

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
}
