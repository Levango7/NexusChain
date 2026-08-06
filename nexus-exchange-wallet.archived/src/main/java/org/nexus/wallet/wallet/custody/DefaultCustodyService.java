package org.nexus.wallet.wallet.custody;

import org.nexus.sdk.wallet.WalletTier;
import org.nexus.wallet.wallet.approval.WithdrawalApprovalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Default custody service implementation for the hot/warm/cold tiering model.
 *
 * <p>Maintains in-memory balances for the hot and cold wallets and enforces
 * the {@link CustodyPolicy} caps:</p>
 * <ul>
 *   <li>{@link #depositToCold}：校验热钱包余额充足且不突破冷钱包上限（
 *       {@link CustodyPolicy#getColdWalletCap()}）→ 热→冷转账（模拟链上交易哈希）</li>
 *   <li>{@link #withdrawFromCold}：校验冷钱包余额充足 + 多签审批——approvalId 必须指向
 *       {@link WithdrawalApprovalService} 中一条已 APPROVED 的审批记录，且审批在放行时
 *       被消耗（APPROVED → EXECUTED），防止同一 approvalId 重放多次出金 → 冷→热转账
 *       （模拟链上交易哈希）。审批服务缺失时冷钱包出金整体禁用（fail closed）。</li>
 *   <li>{@link #rebalance}：按策略自动归集——热钱包超阈值则扫入目标层级，
 *       低于下限则从冷钱包回补（内部受控路径，不经过对外审批接口、不伪造审批 ID）</li>
 * </ul>
 *
 * <p>余额与转账为进程内内存账本；链上转账当前为模拟交易哈希（SIMULATED 前缀），
 * 接入 MPC 签名管道与链上广播后替换。</p>
 */
@Service
public class DefaultCustodyService implements CustodyService {

    private static final Logger log = LoggerFactory.getLogger(DefaultCustodyService.class);

    private final CustodyPolicy custodyPolicy;

    /** Multi-sig withdrawal approval workflow; cold withdrawals are disabled when absent. */
    private final WithdrawalApprovalService withdrawalApprovalService;

    private final AtomicReference<BigDecimal> hotBalance = new AtomicReference<BigDecimal>(BigDecimal.ZERO);
    private final AtomicReference<BigDecimal> coldBalance = new AtomicReference<BigDecimal>(BigDecimal.ZERO);

    public DefaultCustodyService(
            org.springframework.beans.factory.ObjectProvider<CustodyPolicy> custodyPolicyProvider,
            org.springframework.beans.factory.ObjectProvider<WithdrawalApprovalService> withdrawalApprovalServiceProvider) {
        // CustodyPolicy 为可选配置：容器未提供时使用 null（rebalance 自动跳过策略分支）
        this.custodyPolicy = custodyPolicyProvider.getIfAvailable();
        // 审批服务为可选注入：缺失时冷钱包出金整体拒绝（fail closed），绝不无审批放行
        this.withdrawalApprovalService = withdrawalApprovalServiceProvider.getIfAvailable();
    }

    /**
     * Seed balances for testing / initialization.
     *
     * @param hot  initial hot wallet balance
     * @param cold initial cold wallet balance
     */
    public void seedBalances(BigDecimal hot, BigDecimal cold) {
        if (hot != null) hotBalance.set(hot);
        if (cold != null) coldBalance.set(cold);
    }

    @Override
    public String depositToCold(String address, BigDecimal amount) {
        if (address == null || address.isEmpty()) {
            throw new IllegalArgumentException("cold wallet address is required");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        BigDecimal currentHot = hotBalance.get();
        if (currentHot.compareTo(amount) < 0) {
            throw new IllegalStateException(
                    "insufficient hot balance: have=" + currentHot + ", need=" + amount);
        }
        // Enforce cold wallet cap if configured
        BigDecimal coldCap = custodyPolicy == null ? null : custodyPolicy.getColdWalletCap();
        if (coldCap != null && coldBalance.get().add(amount).compareTo(coldCap) > 0) {
            throw new IllegalStateException(
                    "cold wallet cap breached: cap=" + coldCap + ", projected=" + coldBalance.get().add(amount));
        }

        hotBalance.set(currentHot.subtract(amount));
        coldBalance.set(coldBalance.get().add(amount));
        String txHash = "SIMULATED-" + UUID.randomUUID().toString().replace("-", "");
        log.info("Deposit hot->cold: address={}, amount={}, txHash={}, hot={}, cold={}",
                address, amount, txHash, hotBalance.get(), coldBalance.get());
        return txHash;
    }

    @Override
    public String withdrawFromCold(String address, BigDecimal amount, String approvalId) {
        if (address == null || address.isEmpty()) {
            throw new IllegalArgumentException("cold wallet address is required");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (approvalId == null || approvalId.isEmpty()) {
            throw new IllegalArgumentException("multi-sig approvalId is required");
        }
        if (withdrawalApprovalService == null) {
            throw new IllegalStateException(
                    "no withdrawal approval service configured; cold withdrawals are disabled");
        }
        BigDecimal currentCold = coldBalance.get();
        if (currentCold.compareTo(amount) < 0) {
            throw new IllegalStateException(
                    "insufficient cold balance: have=" + currentCold + ", need=" + amount);
        }
        // 多签审批闸门：approvalId 必须对应审批流中一条已 APPROVED 的记录。
        // executeApprovedWithdrawal 对「不存在」的审批抛 IllegalArgumentException，
        // 对「未达审批阈值（非 APPROVED）」的审批抛 IllegalStateException——拒绝放行；
        // 对已批准的审批则置为 EXECUTED——消耗该审批，防止同一 approvalId 重放。
        // 余额校验在前，余额不足时不会消耗审批。
        withdrawalApprovalService.executeApprovedWithdrawal(approvalId);

        String txHash = transferColdToHotInternal(address, amount);
        log.info("Cold withdrawal authorized by approvalId={}, txHash={}", approvalId, txHash);
        return txHash;
    }

    /**
     * Internal cold→hot transfer routine.
     *
     * <p>仅供 {@link #withdrawFromCold}（已通过审批闸门）与策略自动再平衡
     * （{@link #rebalance}，内部受控路径）调用，禁止作为对外接口暴露。</p>
     */
    private String transferColdToHotInternal(String address, BigDecimal amount) {
        if (address == null || address.isEmpty()) {
            throw new IllegalArgumentException("cold wallet address is required");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        BigDecimal currentCold = coldBalance.get();
        if (currentCold.compareTo(amount) < 0) {
            throw new IllegalStateException(
                    "insufficient cold balance: have=" + currentCold + ", need=" + amount);
        }

        coldBalance.set(currentCold.subtract(amount));
        hotBalance.set(hotBalance.get().add(amount));
        String txHash = "SIMULATED-" + UUID.randomUUID().toString().replace("-", "");
        log.info("Withdraw cold->hot: address={}, amount={}, txHash={}, hot={}, cold={}",
                address, amount, txHash, hotBalance.get(), coldBalance.get());
        return txHash;
    }

    @Override
    public BigDecimal getHotBalance() {
        return hotBalance.get();
    }

    @Override
    public BigDecimal getColdBalance() {
        return coldBalance.get();
    }

    @Override
    public void rebalance(WalletTier target) {
        if (custodyPolicy == null) {
            log.warn("Rebalance skipped: no custody policy configured");
            return;
        }
        BigDecimal hot = hotBalance.get();

        // Auto-sweep: hot balance exceeds threshold → sweep excess to target tier
        BigDecimal sweepThreshold = custodyPolicy.getAutoSweepThreshold();
        if (sweepThreshold != null && hot.compareTo(sweepThreshold) > 0) {
            BigDecimal excess = hot.subtract(sweepThreshold);
            WalletTier sweepTarget = custodyPolicy.getSweepTarget() == null
                    ? WalletTier.COLD : custodyPolicy.getSweepTarget();
            if (sweepTarget == WalletTier.COLD) {
                try {
                    depositToCold("cold-sweep", excess);
                    log.info("Rebalance swept {} to COLD", excess);
                } catch (Exception e) {
                    log.warn("Rebalance sweep to COLD failed: {}", e.getMessage());
                }
            }
        }

        // Floor pull: hot balance below operational floor → pull from cold
        BigDecimal floor = custodyPolicy.getHotWalletFloor();
        if (floor != null && hotBalance.get().compareTo(floor) < 0) {
            BigDecimal deficit = floor.subtract(hotBalance.get());
            if (coldBalance.get().compareTo(deficit) >= 0) {
                try {
                    // 内部受控路径：自动再平衡不伪造审批 ID，也不经过对外审批接口
                    transferColdToHotInternal("hot-floor", deficit);
                    log.info("Rebalance pulled {} from COLD to meet floor", deficit);
                } catch (Exception e) {
                    log.warn("Rebalance floor pull failed: {}", e.getMessage());
                }
            } else {
                log.warn("Rebalance floor pull skipped: cold balance insufficient");
            }
        }
    }
}
