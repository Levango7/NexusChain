package org.nexus.oracle.governance;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link Treasury} 默认实现。
 *
 * <p>支出必须关联一个已通过（PASSED / EXECUTED）的 {@code TREASURY_SPEND}
 * 提案，确保可审计；余额不足或提案校验失败时拒绝支出。当前为进程内账本，
 * 后续接入链上国库合约时替换余额查询与转账层。
 */
@Slf4j
@Service
public class DefaultTreasury implements Treasury {

    /** 国库余额 */
    private final AtomicReference<BigDecimal> balance = new AtomicReference<>(BigDecimal.ZERO);

    /** 支出历史（spendId → 记录） */
    private final Map<String, Map<String, Object>> spends = new ConcurrentHashMap<>();

    /** 支出历史顺序（spendId 列表） */
    private final List<String> spendOrder = new CopyOnWriteArrayList<>();

    /** 治理服务（用于校验支出提案） */
    private final GovernanceService governanceService;

    public DefaultTreasury(GovernanceService governanceService) {
        this.governanceService = governanceService;
    }

    /**
     * 初始注资（链上国库同步 / 测试用）。
     *
     * @param initialBalance 初始余额
     */
    public void fund(BigDecimal initialBalance) {
        if (initialBalance != null && initialBalance.compareTo(BigDecimal.ZERO) > 0) {
            balance.set(initialBalance);
            log.info("Treasury funded: balance={}", initialBalance);
        }
    }

    @Override
    public BigDecimal balance() {
        return balance.get();
    }

    @Override
    public boolean spend(BigDecimal amount, String to, String proposalId) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        if (to == null || to.isBlank() || proposalId == null || proposalId.isBlank()) {
            return false;
        }
        // 校验提案：必须存在、类型为 TREASURY_SPEND、状态为 PASSED / EXECUTED
        Proposal proposal = governanceService.getProposal(proposalId);
        if (proposal == null || proposal.getType() != Proposal.Type.TREASURY_SPEND) {
            log.debug("Spend rejected: proposal not found or wrong type: {}", proposalId);
            return false;
        }
        ProposalState state = proposal.getState();
        if (state != ProposalState.PASSED && state != ProposalState.EXECUTED) {
            log.debug("Spend rejected: proposal not passed, state={}", state);
            return false;
        }
        // 余额校验与扣减
        BigDecimal current = balance.get();
        if (current.compareTo(amount) < 0) {
            log.debug("Spend rejected: insufficient balance, current={}, requested={}", current, amount);
            return false;
        }
        balance.set(current.subtract(amount));

        String spendId = "SPEND-" + UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("spendId", spendId);
        record.put("amount", amount);
        record.put("to", to);
        record.put("proposalId", proposalId);
        record.put("timestamp", Instant.now().toString());
        spends.put(spendId, record);
        spendOrder.add(spendId);
        log.info("Treasury spend executed: spendId={}, amount={}, to={}, proposalId={}, remaining={}",
                spendId, amount, to, proposalId, balance.get());
        return true;
    }

    @Override
    public List<Map<String, Object>> getHistory() {
        return spendOrder.stream()
                .map(spends::get)
                .filter(r -> r != null)
                .toList();
    }

    @Override
    public Map<String, Object> getSpend(String spendId) {
        return spendId == null ? null : spends.get(spendId);
    }
}
