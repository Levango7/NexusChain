package org.nexus.l2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * L1↔L2 桥合约默认实现。
 *
 * <p>骨架实现：记录存款 / 提款 / 状态根提交操作，
     * 实际跨层资产锁定与铸造待接入 L1 合约交互。</p>
 *
 * @since 1.2
 */
@Component
public class DefaultL2BridgeContract implements L2BridgeContract {

    private static final Logger logger = LoggerFactory.getLogger(DefaultL2BridgeContract.class);

    private final Map<String, L2TransactionStatus> deposits = new ConcurrentHashMap<>();
    private final Map<String, L2TransactionStatus> withdrawals = new ConcurrentHashMap<>();
    private final Map<Long, String> committedRoots = new ConcurrentHashMap<>();

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
        withdrawals.put(withdrawId, L2TransactionStatus.PENDING);
        logger.info("Withdraw {} to {} amount {}, id={}", withdrawId, to, amount, withdrawId);
        return withdrawId;
    }

    @Override
    public boolean submitStateRoot(long batchId, String stateRoot) {
        if (stateRoot == null) {
            return false;
        }
        committedRoots.put(batchId, stateRoot);
        logger.info("State root {} submitted to L1 for batch {}", stateRoot, batchId);
        return true;
    }

    @Override
    public L2TransactionStatus getDepositStatus(String depositId) {
        return deposits.get(depositId);
    }

    @Override
    public L2TransactionStatus getWithdrawStatus(String withdrawId) {
        return withdrawals.get(withdrawId);
    }

    /**
     * 标记提款为已确认（L1 侧释放资产后调用）。
     *
     * @param withdrawId 提款操作 ID
     */
    public void confirmWithdraw(String withdrawId) {
        withdrawals.put(withdrawId, L2TransactionStatus.CONFIRMED);
        logger.info("Withdraw {} confirmed on L1", withdrawId);
    }

    public String getCommittedRoot(long batchId) {
        return committedRoots.get(batchId);
    }
}