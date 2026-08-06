package org.nexus.l2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * L1↔L2 桥合约默认实现。
 *
 * <p>记录存款 / 提款 / 状态根提交操作，状态根提交接入 L1 合约客户端。
 * 提款解锁通过 {@link #finalizeWithdraw} 串联：批次 VERIFIED → 验证 Merkle 证明 → 释放资金。</p>
 *
 * @since 1.2
 */
@Component
public class DefaultL2BridgeContract implements L2BridgeContract {

    private static final Logger logger = LoggerFactory.getLogger(DefaultL2BridgeContract.class);

    @Autowired(required = false)
    private L1ContractClient l1ContractClient;

    private final Map<String, L2TransactionStatus> deposits = new ConcurrentHashMap<>();
    private final Map<String, WithdrawRecord> withdrawals = new ConcurrentHashMap<>();
    private final Map<Long, String> committedRoots = new ConcurrentHashMap<>();

    /** 批次 -> 关联的提款 tx 列表 */
    private final Map<Long, List<L2Transaction>> batchWithdraws = new ConcurrentHashMap<>();

    /** 批次 -> 是否已 VERIFIED */
    private final Map<Long, Boolean> batchVerified = new ConcurrentHashMap<>();

    /** 批次 -> tx Merkle 根（用于 finalizeWithdraw 验证） */
    private final Map<Long, String> batchTxRoots = new ConcurrentHashMap<>();

    /** 已释放资金的提款 */
    private final Map<String, Boolean> releasedWithdrawals = new ConcurrentHashMap<>();

    @Override
    public String deposit(String from, BigInteger amount) {
        if (from == null || amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Invalid deposit request");
        }
        String depositId = "dep-" + UUID.randomUUID();
        deposits.put(depositId, L2TransactionStatus.CONFIRMED);
        logger.info("Deposit {} from {} amount {}, id={}", depositId, from, amount, depositId);
        return depositId;
    }

    @Override
    public String withdraw(String to, BigInteger amount) {
        if (to == null || amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Invalid withdraw request");
        }
        String withdrawId = "wd-" + UUID.randomUUID();
        WithdrawRecord record = new WithdrawRecord(withdrawId, to, amount, L2TransactionStatus.PENDING);
        withdrawals.put(withdrawId, record);
        logger.info("Withdraw {} to {} amount {}, id={}", withdrawId, to, amount, withdrawId);
        return withdrawId;
    }

    @Override
    public boolean submitStateRoot(long batchId, String stateRoot) {
        if (stateRoot == null) {
            return false;
        }
        // 优先调用 L1 合约客户端上链
        if (l1ContractClient != null) {
            boolean ok = l1ContractClient.submitStateRootToL1(batchId, stateRoot);
            if (!ok) {
                return false;
            }
        }
        // 内存 fallback 记录
        committedRoots.put(batchId, stateRoot);
        logger.info("State root {} submitted for batch {} (l1={})",
                stateRoot, batchId, l1ContractClient != null);
        return true;
    }

    @Override
    public L2TransactionStatus getDepositStatus(String depositId) {
        return deposits.get(depositId);
    }

    @Override
    public L2TransactionStatus getWithdrawStatus(String withdrawId) {
        WithdrawRecord r = withdrawals.get(withdrawId);
        return r == null ? null : r.status;
    }

    /**
     * 标记提款为已确认（L1 侧释放资产后调用）。
     */
    public void confirmWithdraw(String withdrawId) {
        WithdrawRecord r = withdrawals.get(withdrawId);
        if (r != null) {
            r.status = L2TransactionStatus.CONFIRMED;
        }
        releasedWithdrawals.put(withdrawId, true);
        logger.info("Withdraw {} confirmed on L1", withdrawId);
    }

    /**
     * 注册提款 tx 到批次（用于 finalizeWithdrawsForBatch 批量触发）。
     */
    public void registerBatchWithdraw(long batchId, L2Transaction withdrawTx) {
        if (withdrawTx == null) {
            return;
        }
        batchWithdraws.computeIfAbsent(batchId, k -> new ArrayList<>()).add(withdrawTx);
        logger.debug("Registered withdraw tx {} to batch {}", withdrawTx.getTxHash(), batchId);
    }

    /**
     * 标记批次为已 VERIFIED（由 FraudProofVerifier.finalizeBatch 调用）。
     */
    public void markBatchVerified(long batchId) {
        batchVerified.put(batchId, true);
        if (l1ContractClient != null) {
            l1ContractClient.finalizeBatchOnL1(batchId);
        }
        logger.info("Batch {} marked VERIFIED on bridge", batchId);
    }

    /**
     * 设置批次 tx Merkle 根（用于 finalizeWithdraw 验证 merkle proof）。
     */
    public void setBatchTxRoot(long batchId, String txRoot) {
        batchTxRoots.put(batchId, txRoot);
    }

    /**
     * 提款解锁：验证批次 VERIFIED + Merkle 证明，释放资金给目标地址。
     *
     * @param batchId      批次 ID
     * @param withdrawTx   提款交易
     * @param merkleProof  提款 tx 在批次中的 Merkle 证明（null 跳过 proof 验证，用于内部触发）
     * @return 释放成功返回 true
     */
    public boolean finalizeWithdraw(long batchId, L2Transaction withdrawTx, MerkleProof merkleProof) {
        if (withdrawTx == null) {
            return false;
        }
        // a. 验证 batch.status == VERIFIED
        if (!batchVerified.getOrDefault(batchId, false)) {
            logger.warn("finalizeWithdraw: batch {} not VERIFIED", batchId);
            return false;
        }
        // b. 验证 merkleProof 证明 withdrawTx 在 batch 内
        if (merkleProof != null) {
            String expectedRoot = batchTxRoots.get(batchId);
            if (expectedRoot == null || !MerklePatriciaTrie.verifyProof(merkleProof, expectedRoot)) {
                logger.warn("finalizeWithdraw: merkle proof invalid for batch {}", batchId);
                return false;
            }
        }
        // c. 释放资金给目标地址
        String withdrawId = "wd-" + withdrawTx.getTxHash();
        WithdrawRecord r = withdrawals.get(withdrawId);
        if (r != null) {
            r.status = L2TransactionStatus.CONFIRMED;
        }
        releasedWithdrawals.put(withdrawId, true);
        withdrawTx.setStatus(L2TransactionStatus.CONFIRMED);
        logger.info("Withdraw {} finalized for batch {} (tx={})", withdrawId, batchId, withdrawTx.getTxHash());
        return true;
    }

    /**
     * 触发批次所有提款的 finalizeWithdraw（由 FraudProofVerifier.finalizeBatch 调用）。
     *
     * @param batchId 批次 ID
     * @return 成功 finalize 的提款数量
     */
    public int finalizeWithdrawsForBatch(long batchId) {
        List<L2Transaction> txs = batchWithdraws.get(batchId);
        if (txs == null || txs.isEmpty()) {
            logger.debug("No withdraws to finalize for batch {}", batchId);
            return 0;
        }
        int count = 0;
        for (L2Transaction tx : txs) {
            if (finalizeWithdraw(batchId, tx, null)) {
                count++;
            }
        }
        logger.info("Finalized {} withdraws for batch {}", count, batchId);
        return count;
    }

    public String getCommittedRoot(long batchId) {
        return committedRoots.get(batchId);
    }

    public boolean isWithdrawReleased(String withdrawId) {
        return releasedWithdrawals.getOrDefault(withdrawId, false);
    }

    public boolean isBatchVerified(long batchId) {
        return batchVerified.getOrDefault(batchId, false);
    }

    /** 提款记录 */
    private static final class WithdrawRecord {
        final String withdrawId;
        final String to;
        final BigInteger amount;
        volatile L2TransactionStatus status;

        WithdrawRecord(String withdrawId, String to, BigInteger amount, L2TransactionStatus status) {
            this.withdrawId = withdrawId;
            this.to = to;
            this.amount = amount;
            this.status = status;
        }
    }
}
