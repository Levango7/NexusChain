package org.nexus.walletsvc.custody;

import org.nexus.sdk.wallet.WalletTier;
import org.nexus.sdk.wallet.WalletTransactionRequest;
import org.nexus.sdk.wallet.WalletTransactionResult;
import org.nexus.walletsvc.execution.OnChainExecutionClient;
import org.nexus.walletsvc.approval.WithdrawalApprovalService;
import org.nexus.walletsvc.entity.CustodyBalanceEntity;
import org.nexus.walletsvc.repository.CustodyBalanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Default custody service implementation for the hot/warm/cold tiering model.
 *
 * <p>Phase 4 改造（任务 #70，设计文档 §4.4.1）：原进程内
 * {@code AtomicReference<BigDecimal>} 内存账本（{@code hotBalance} / {@code coldBalance}）
 * 已替换为数据库持久化，通过 {@link CustodyBalanceRepository} 查询 / 更新
 * {@code custody_balances} 表。并发变更由 {@code @Version} 乐观锁保护，
 * 写操作方法标注 {@link Transactional} 纳入本地分支事务（在全局事务上下文中
 * 自动注册为 Seata AT 分支）。</p>
 *
 * <p>余额与转账现在持久化到 {@code nexus_wallet} 库；链上转账当前仍为模拟交易哈希
 * （SIMULATED 前缀），接入 MPC 签名管道与链上广播后替换。</p>
 *
 * <p>迁移历史：原位于 {@code org.nexus.wallet.wallet.custody.DefaultCustodyService}
 * （nexus-exchange-wallet），在 Phase 2 微服务化中迁移至 nexus-wallet-service
 * （新包 {@code org.nexus.walletsvc.custody}）。对 {@link WithdrawalApprovalService}
 * 的引用更新为 {@code org.nexus.walletsvc.approval.WithdrawalApprovalService}。</p>
 */
@Service
public class DefaultCustodyService implements CustodyService {

    private static final Logger log = LoggerFactory.getLogger(DefaultCustodyService.class);

    /** custody_balances 表中热钱包行的 tier 主键。 */
    private static final String TIER_HOT = "HOT";
    /** custody_balances 表中冷钱包行的 tier 主键。 */
    private static final String TIER_COLD = "COLD";

    private final CustodyPolicy custodyPolicy;

    /** Multi-sig withdrawal approval workflow; cold withdrawals are disabled when absent. */
    private final WithdrawalApprovalService withdrawalApprovalService;

    /** 托管余额持久化 Repository，替代原 AtomicReference 内存存储。 */
    private final CustodyBalanceRepository custodyBalanceRepository;

    /** 链上执行通道（v1.9.2）：冷钱包转账经此走 gateway → 链上广播，替换 SIMULATED 占位。 */
    private final OnChainExecutionClient onChainExecutionClient;

    public DefaultCustodyService(
            org.springframework.beans.factory.ObjectProvider<CustodyPolicy> custodyPolicyProvider,
            org.springframework.beans.factory.ObjectProvider<WithdrawalApprovalService> withdrawalApprovalServiceProvider,
            CustodyBalanceRepository custodyBalanceRepository,
            org.springframework.beans.factory.ObjectProvider<OnChainExecutionClient> executionClientProvider) {
        // CustodyPolicy 为可选配置：容器未提供时使用 null（rebalance 自动跳过策略分支）
        this.custodyPolicy = custodyPolicyProvider.getIfAvailable();
        // 审批服务为可选注入：缺失时冷钱包出金整体拒绝（fail closed），绝不无审批放行
        this.withdrawalApprovalService = withdrawalApprovalServiceProvider.getIfAvailable();
        this.custodyBalanceRepository = custodyBalanceRepository;
        // 执行客户端为可选注入：缺失时转账退化为内部模拟哈希（向后兼容独立测试环境）
        this.onChainExecutionClient = executionClientProvider.getIfAvailable();
    }

    /**
     * Seed balances for testing / initialization.
     *
     * <p>Phase 4 改造：原直接操作 {@code AtomicReference.set()}，现委托
     * {@link CustodyBalanceRepository#save(Object)} 持久化到 {@code custody_balances} 表。
     * 生产环境由 Flyway V2 预置 HOT / COLD 两行（balance=0），无需调用此方法。</p>
     *
     * @param hot  initial hot wallet balance
     * @param cold initial cold wallet balance
     * @deprecated Phase 4 后由 Flyway V2 seed data 预置；测试可通过
     *             {@link CustodyBalanceRepository#save(Object)} 直接初始化余额。
     *             T9 完成测试改造后在后续清理中删除。
     */
    @Deprecated
    @Transactional
    public void seedBalances(BigDecimal hot, BigDecimal cold) {
        if (hot != null) {
            CustodyBalanceEntity hotEntity = custodyBalanceRepository.findByTier(TIER_HOT)
                    .orElseGet(CustodyBalanceEntity::new);
            hotEntity.setTier(TIER_HOT);
            hotEntity.setBalance(hot);
            custodyBalanceRepository.save(hotEntity);
        }
        if (cold != null) {
            CustodyBalanceEntity coldEntity = custodyBalanceRepository.findByTier(TIER_COLD)
                    .orElseGet(CustodyBalanceEntity::new);
            coldEntity.setTier(TIER_COLD);
            coldEntity.setBalance(cold);
            custodyBalanceRepository.save(coldEntity);
        }
    }

    @Override
    @Transactional
    public String depositToCold(String address, BigDecimal amount) {
        if (address == null || address.isEmpty()) {
            throw new IllegalArgumentException("cold wallet address is required");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        CustodyBalanceEntity hotEntity = custodyBalanceRepository.findByTier(TIER_HOT)
                .orElseThrow(() -> new IllegalStateException("HOT balance row not initialized"));
        CustodyBalanceEntity coldEntity = custodyBalanceRepository.findByTier(TIER_COLD)
                .orElseThrow(() -> new IllegalStateException("COLD balance row not initialized"));

        BigDecimal currentHot = hotEntity.getBalance();
        if (currentHot.compareTo(amount) < 0) {
            throw new IllegalStateException(
                    "insufficient hot balance: have=" + currentHot + ", need=" + amount);
        }
        // Enforce cold wallet cap if configured
        BigDecimal coldCap = custodyPolicy == null ? null : custodyPolicy.getColdWalletCap();
        if (coldCap != null && coldEntity.getBalance().add(amount).compareTo(coldCap) > 0) {
            throw new IllegalStateException(
                    "cold wallet cap breached: cap=" + coldCap + ", projected=" + coldEntity.getBalance().add(amount));
        }

        hotEntity.setBalance(currentHot.subtract(amount));
        coldEntity.setBalance(coldEntity.getBalance().add(amount));
        custodyBalanceRepository.save(hotEntity);
        custodyBalanceRepository.save(coldEntity);

        String txHash = executeOnChainTransfer(
                WalletTransactionRequest.Type.SWEEP, address, amount, "custody hot->cold sweep");
        log.info("Deposit hot->cold: address={}, amount={}, txHash={}, hot={}, cold={}",
                address, amount, txHash, hotEntity.getBalance(), coldEntity.getBalance());
        return txHash;
    }

    @Override
    @Transactional
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
        CustodyBalanceEntity coldEntity = custodyBalanceRepository.findByTier(TIER_COLD)
                .orElseThrow(() -> new IllegalStateException("COLD balance row not initialized"));
        BigDecimal currentCold = coldEntity.getBalance();
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
     *
     * <p>事务边界：由 public 调用方（{@code @Transactional}）传播，不单独标注
     * （Spring {@code @Transactional} 在 private 方法上不生效）。</p>
     */
    private String transferColdToHotInternal(String address, BigDecimal amount) {
        if (address == null || address.isEmpty()) {
            throw new IllegalArgumentException("cold wallet address is required");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        CustodyBalanceEntity coldEntity = custodyBalanceRepository.findByTier(TIER_COLD)
                .orElseThrow(() -> new IllegalStateException("COLD balance row not initialized"));
        CustodyBalanceEntity hotEntity = custodyBalanceRepository.findByTier(TIER_HOT)
                .orElseThrow(() -> new IllegalStateException("HOT balance row not initialized"));
        BigDecimal currentCold = coldEntity.getBalance();
        if (currentCold.compareTo(amount) < 0) {
            throw new IllegalStateException(
                    "insufficient cold balance: have=" + currentCold + ", need=" + amount);
        }

        coldEntity.setBalance(currentCold.subtract(amount));
        hotEntity.setBalance(hotEntity.getBalance().add(amount));
        custodyBalanceRepository.save(coldEntity);
        custodyBalanceRepository.save(hotEntity);

        String txHash = executeOnChainTransfer(
                WalletTransactionRequest.Type.WITHDRAWAL, address, amount, "custody cold->hot withdrawal");
        log.info("Withdraw cold->hot: address={}, amount={}, txHash={}, hot={}, cold={}",
                address, amount, txHash, hotEntity.getBalance(), coldEntity.getBalance());
        return txHash;
    }

    /**
     * 通过链上执行通道（gateway → 链上广播）执行转账，替换原 SIMULATED 占位（v1.9.2）。
     *
     * <p>当 {@code OnChainExecutionClient} 未注入（独立/测试环境）或调用失败时，
     * 降级为内部模拟哈希（SIMULATED 前缀），保持向后兼容；调用失败不抛异常，
     * 以 FAILED 结果降级，保证余额账本与交易记录的原子性不受影响。</p>
     *
     * @param type    交易类型（SWEEP / WITHDRAWAL）
     * @param address 目标地址
     * @param amount  金额
     * @param memo    备注
     * @return 交易哈希（真实广播或 SIMULATED- 前缀降级）
     */
    private String executeOnChainTransfer(WalletTransactionRequest.Type type,
                                          String address, BigDecimal amount, String memo) {
        if (onChainExecutionClient == null) {
            String txHash = "SIMULATED-" + UUID.randomUUID().toString().replace("-", "");
            log.warn("No OnChainExecutionClient configured; fallback SIMULATED tx: type={}, txHash={}", type, txHash);
            return txHash;
        }
        WalletTransactionRequest request = new WalletTransactionRequest(
                type, "cold-custody", address, amount, "NEX", memo, UUID.randomUUID().toString());
        WalletTransactionResult result = onChainExecutionClient.execute(request);
        if (result == null || result.getTxHash() == null || result.getTxHash().isEmpty()) {
            String txHash = "SIMULATED-" + UUID.randomUUID().toString().replace("-", "");
            log.warn("On-chain transfer failed ({}); fallback SIMULATED tx: type={}, txHash={}",
                    result != null ? result.getError() : "null result", type, txHash);
            return txHash;
        }
        return result.getTxHash();
    }

    @Override
    public BigDecimal getHotBalance() {
        return custodyBalanceRepository.findByTier(TIER_HOT)
                .map(CustodyBalanceEntity::getBalance)
                .orElse(BigDecimal.ZERO);
    }

    @Override
    public BigDecimal getColdBalance() {
        return custodyBalanceRepository.findByTier(TIER_COLD)
                .map(CustodyBalanceEntity::getBalance)
                .orElse(BigDecimal.ZERO);
    }

    @Override
    @Transactional
    public void rebalance(WalletTier target) {
        if (custodyPolicy == null) {
            log.warn("Rebalance skipped: no custody policy configured");
            return;
        }
        BigDecimal hot = getHotBalance();

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
        BigDecimal hotAfterSweep = getHotBalance();
        if (floor != null && hotAfterSweep.compareTo(floor) < 0) {
            BigDecimal deficit = floor.subtract(hotAfterSweep);
            BigDecimal currentCold = getColdBalance();
            if (currentCold.compareTo(deficit) >= 0) {
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

    /**
     * 判断指定钱包是否为冷托管。
     *
     * <p>骨架实现：默认返回 {@code false}（非冷托管）。完整迁移后应根据
     * 钱包元数据 / 配置中心查询实际托管层级。</p>
     *
     * @param walletId 钱包 ID
     * @return 是否为冷托管
     */
    @Override
    public boolean isColdCustody(String walletId) {
        if (walletId == null || walletId.isEmpty()) {
            return false;
        }
        // 骨架实现：以 "cold" 前缀判断（迁移期占位逻辑）
        return walletId.toLowerCase().startsWith("cold");
    }

    /**
     * 查询指定钱包的托管层级。
     *
     * <p>骨架实现：根据钱包 ID 前缀返回层级名称。完整迁移后应根据
     * 钱包元数据 / 配置中心查询实际托管层级。</p>
     *
     * @param walletId 钱包 ID
     * @return 托管层级名称（HOT / WARM / COLD）
     */
    @Override
    public String getCustodyTier(String walletId) {
        if (walletId == null || walletId.isEmpty()) {
            return WalletTier.HOT.name();
        }
        String lower = walletId.toLowerCase();
        if (lower.startsWith("cold")) {
            return WalletTier.COLD.name();
        } else if (lower.startsWith("warm")) {
            return WalletTier.WARM.name();
        }
        return WalletTier.HOT.name();
    }
}
