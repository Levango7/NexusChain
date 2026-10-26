package org.nexus.walletsvc.custody;

import org.nexus.sdk.wallet.WalletTier;
import org.nexus.sdk.wallet.WalletTransactionRequest;
import org.nexus.sdk.wallet.WalletTransactionResult;
import org.nexus.walletsvc.execution.OnChainExecutionClient;
import org.nexus.walletsvc.approval.WithdrawalApprovalService;
import org.nexus.walletsvc.entity.CustodyBalanceEntity;
import org.nexus.walletsvc.entity.CustodyTransferPendingEntity.PendingStatus;
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
 * <p>余额与转账已持久化到 {@code nexus_wallet} 库；链上转账经
 * {@link org.nexus.walletsvc.execution.OnChainExecutionClient}（gateway → 链上广播）
 * 执行，fail-closed：通道缺失/确定失败即抛异常回滚，绝不伪造交易哈希（v1.9.2 起）。</p>
 *
 * <p><strong>「拿不到哈希就回滚」的例外（2026-10-25，Board §五）</strong>：执行通道返回
 * {@code EXECUTED_UNVERIFIED}（签名已执行但取不到哈希）时<b>不回滚</b>冷热余额，
 * 改由 {@link CustodyTransferPendingRecorder} 登记 {@code VERIFY_REQUIRED} 待人工核对；
 * 返回 {@code AWAITING_APPROVAL}（大额转人工审批）时照常回滚，但同样登记一条
 * {@code APPROVAL_OPEN}，避免悬空审批无人知晓。两种结果都绝不当成功，也不自动重试。</p>
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

    /**
     * 待处置记录写入器：可选注入（纯单测路径可为 null）。
     *
     * <p>缺失时不阻断资金动作——回滚 / 不回滚的决策只取决于执行结果，
     * 但会打 ERROR 说明「这笔需要人工核对的出金没能落表」。</p>
     */
    private final CustodyTransferPendingRecorder pendingTransferRecorder;

    public DefaultCustodyService(
            org.springframework.beans.factory.ObjectProvider<CustodyPolicy> custodyPolicyProvider,
            org.springframework.beans.factory.ObjectProvider<WithdrawalApprovalService> withdrawalApprovalServiceProvider,
            CustodyBalanceRepository custodyBalanceRepository,
            org.springframework.beans.factory.ObjectProvider<OnChainExecutionClient> executionClientProvider,
            org.springframework.beans.factory.ObjectProvider<CustodyTransferPendingRecorder> pendingTransferRecorderProvider) {
        // CustodyPolicy 为可选配置：容器未提供时使用 null（rebalance 自动跳过策略分支）
        this.custodyPolicy = custodyPolicyProvider.getIfAvailable();
        // 审批服务为可选注入：缺失时冷钱包出金整体拒绝（fail closed），绝不无审批放行
        this.withdrawalApprovalService = withdrawalApprovalServiceProvider.getIfAvailable();
        this.custodyBalanceRepository = custodyBalanceRepository;
        // 执行客户端为可选注入：缺失时托管出金整体拒绝（fail closed），绝不产出模拟哈希冒充上链
        this.onChainExecutionClient = executionClientProvider.getIfAvailable();
        this.pendingTransferRecorder = pendingTransferRecorderProvider.getIfAvailable();
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
    @Transactional(noRollbackFor = CustodyTransferUnverifiedException.class)
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
    @Transactional(noRollbackFor = CustodyTransferUnverifiedException.class)
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
     * 通过链上执行通道（gateway → 链上广播）执行转账。
     *
     * <p><b>fail-closed（资金安全）</b>：当 {@code OnChainExecutionClient} 未注入或
     * 链上<b>确定失败</b>（返回空哈希且非下述两种状态）时，<b>抛异常而非返回伪造的
     * {@code SIMULATED-} 哈希</b>。本方法在调用方（{@code depositToCold} /
     * {@code transferColdToHotInternal}）的 {@code @Transactional} 事务内执行，
     * 抛异常将触发回滚，已扣减的冷热余额随之撤销，
     * 保证账本绝不会出现「账上有、链上无」的对账缺口。</p>
     *
     * <p><b>三种结果不能混为一谈</b>（Board 裁决 20261025-0955 §五）：</p>
     * <ul>
     *   <li>{@code AWAITING_APPROVAL}：大额转人工审批，钱没出 ⇒ 照旧抛异常回滚，
     *       并登记 {@code APPROVAL_OPEN}（签名侧审批请求仍开着，必须留痕）。
     *       <b>不可自动重试</b>：审批通过后由签名侧执行，重试等于同一意图二次授权。</li>
     *   <li>{@code EXECUTED_UNVERIFIED}：签名已执行但取不到哈希，钱可能已经出 ⇒
     *       抛 {@link CustodyTransferUnverifiedException}（调用方以 {@code noRollbackFor}
     *       放行）<b>保持余额调整提交</b>，登记 {@code VERIFY_REQUIRED} 待人工核对链上。</li>
     *   <li>其余无哈希结果：确定未动账 ⇒ 抛 {@code IllegalStateException} 回滚，不留痕。</li>
     * </ul>
     *
     * <p>沙箱 / 本地联调请改用 {@code nexus.wallet.execution.sandbox=true} 的
     * {@link org.nexus.walletsvc.execution.HttpOnChainExecutionClient} 沙箱通道，
     * 由该通道显式产出带 {@code simulated=true} 标记的响应，而非在生产资金路径上伪造哈希。</p>
     *
     * @param type    交易类型（SWEEP / WITHDRAWAL）
     * @param address 目标地址
     * @param amount  金额
     * @param memo    备注
     * @return 链上真实交易哈希（永不为 {@code SIMULATED-} 前缀）
     * @throws IllegalStateException             执行通道缺失或确定失败（触发事务回滚）
     * @throws CustodyTransferUnverifiedException 已执行但不可核对（不回滚，转人工）
     */
    private String executeOnChainTransfer(WalletTransactionRequest.Type type,
                                          String address, BigDecimal amount, String memo) {
        if (onChainExecutionClient == null) {
            log.error("Fail-closed: no OnChainExecutionClient configured; aborting custodial transfer type={}, address={}, amount={}",
                    type, address, amount);
            throw new IllegalStateException(
                    "on-chain execution channel not configured; custodial transfer aborted (rollback)");
        }
        // requestId 按「一次出金意图」生成：三种结果都禁止自动重发（签名侧会把超时的
        // EXECUTING 回退成 APPROVED，重发即双花），所以不需要跨调用的稳定键；
        // 它同时是 gateway 审批链接的 requestKey 后缀（execution:<TYPE>:<requestId>），
        // 人工可凭 custody_transfer_pending.request_key 直接定位到那条链接。
        WalletTransactionRequest request = new WalletTransactionRequest(
                type, "cold-custody", address, amount, "NEX", memo, UUID.randomUUID().toString());
        WalletTransactionResult result = onChainExecutionClient.execute(request);
        WalletTransactionResult.Status status = result == null ? null : result.getStatus();
        String approvalId = result == null ? null : result.getApprovalId();
        String error = result != null ? result.getError() : "null result";

        if (status == WalletTransactionResult.Status.AWAITING_APPROVAL) {
            recordPendingTransfer(request, PendingStatus.APPROVAL_OPEN, approvalId,
                    "大额签名待人工审批；冷热余额已回滚，钱未出账。审批通过后需人工重新发起或驳回。");
            log.error("Fail-closed: on-chain transfer awaiting manual signing approval; aborting custodial "
                            + "transfer (rollback): requestKey={}, approvalId={}, type={}, address={}, amount={}",
                    request.getRequestId(), approvalId, type, address, amount);
            throw new IllegalStateException("on-chain transfer awaiting signing approval: approvalId="
                    + approvalId + " (rollback, no auto-retry)");
        }
        if (status == WalletTransactionResult.Status.EXECUTED_UNVERIFIED) {
            recordPendingTransfer(request, PendingStatus.VERIFY_REQUIRED, approvalId,
                    "签名已执行但无交易哈希，钱可能已转出；冷热余额调整保持提交，禁止自动重试，需人工核对链上。"
                            + (error == null ? "" : " error=" + error));
            log.error("Unverified: on-chain transfer executed without a verifiable txHash; custody balances "
                            + "are NOT rolled back: requestKey={}, approvalId={}, type={}, address={}, amount={}, error={}",
                    request.getRequestId(), approvalId, type, address, amount, error);
            throw new CustodyTransferUnverifiedException(
                    "on-chain transfer executed but unverifiable: approvalId=" + approvalId
                            + " (custody balances kept, manual chain check required)",
                    request.getRequestId(), approvalId);
        }
        String txHash = result == null ? null : result.getTxHash();
        if (txHash == null || txHash.isEmpty()) {
            log.error("Fail-closed: on-chain transfer failed ({}); aborting custodial transfer type={}, address={}, amount={}",
                    error, type, address, amount);
            throw new IllegalStateException(
                    "on-chain transfer failed: " + error + " (rollback)");
        }
        return txHash;
    }

    /**
     * 登记一条托管出金待处置记录（独立事务，见 {@link CustodyTransferPendingRecorder}）。
     *
     * <p>登记失败绝不改变资金决策：落表失败时把核对所需信息打进 ERROR 日志，
     * 人工仍可从日志定位这笔出金。</p>
     */
    private void recordPendingTransfer(WalletTransactionRequest request, PendingStatus status,
                                       String approvalId, String note) {
        if (pendingTransferRecorder == null) {
            log.error("托管出金待处置记录未登记（CustodyTransferPendingRecorder 未注入），需按日志人工核对: "
                            + "requestKey={}, status={}, approvalId={}, type={}, address={}, amount={}",
                    request.getRequestId(), status, approvalId, request.getType(),
                    request.getToAddress(), request.getAmount());
            return;
        }
        try {
            pendingTransferRecorder.record(request, status, approvalId, note);
        } catch (RuntimeException e) {
            log.error("托管出金待处置记录登记失败，需按日志人工核对: requestKey={}, status={}, approvalId={}, error={}",
                    request.getRequestId(), status, approvalId, e.toString(), e);
        }
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

    /**
     * 按策略自动再平衡冷热钱包余额。
     *
     * <p><b>失败语义（2026-10-26 修复）</b>：本方法**不再吞掉转账异常**。
     * 修复前的 {@code catch (Exception e) { log.warn(...) }} 有一个致命后果：
     * {@link #depositToCold} 的执行顺序是「先改冷热余额 → 再调链上执行」，
     * 因此当执行通道返回确定失败（{@code FAILED} / 无 txHash / {@code AWAITING_APPROVAL}）时，
     * 异常被吞掉 ⇒ 事务照常提交 ⇒ <b>钱没出去、账却动了</b>（余额虚增）。</p>
     *
     * <p>现在按执行结果分两种处理：
     * <ul>
     *   <li><b>确定失败</b>（{@code IllegalStateException}）：异常传播 ⇒
     *       {@code @Transactional} 回滚 ⇒ 余额调整撤销，符合「钱没出账不能动」；</li>
     *   <li><b>已执行但不可核对</b>（{@link CustodyTransferUnverifiedException}）：
     *       由 {@code noRollbackFor} 放行提交 ⇒ 余额调整<b>保留</b>（钱可能已出），
     *       异常继续传播让调用方知道需要人工核对，而不是静默返回成功。</li>
     * </ul>
     * 两种结果都不自动重试（重试会二次出金）。</p>
     *
     * <p><b>为什么 {@code noRollbackFor} 必须标在本方法上</b>：
     * {@link #depositToCold} 虽然也有该注解，但它在 {@code rebalance} 内是<b>同类自调用</b>，
     * 不经过 Spring 代理 ⇒ 注解不生效。事务边界在 {@code rebalance} 这里，
     * 所以放行规则必须写在这里。</p>
     */
    @Override
    @Transactional(noRollbackFor = CustodyTransferUnverifiedException.class)
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
                // 不捕获：确定失败必须传播以触发事务回滚（否则钱未出、账已动）。
                String txHash = depositToCold("cold-sweep", excess);
                log.info("Rebalance swept {} to COLD, txHash={}", excess, txHash);
            }
        }

        // Floor pull: hot balance below operational floor → pull from cold
        BigDecimal floor = custodyPolicy.getHotWalletFloor();
        BigDecimal hotAfterSweep = getHotBalance();
        if (floor != null && hotAfterSweep.compareTo(floor) < 0) {
            BigDecimal deficit = floor.subtract(hotAfterSweep);
            BigDecimal currentCold = getColdBalance();
            if (currentCold.compareTo(deficit) >= 0) {
                // 内部受控路径：自动再平衡不伪造审批 ID，也不经过对外审批接口。
                // 不捕获：确定失败必须传播以触发事务回滚（否则钱未出、账已动）。
                String txHash = transferColdToHotInternal("hot-floor", deficit);
                log.info("Rebalance pulled {} from COLD to meet floor, txHash={}", deficit, txHash);
            } else {
                log.warn("Rebalance floor pull skipped: cold balance insufficient");
            }
        }
    }

    /**
     * 判断指定钱包是否为冷托管。
     *
     * <p>#17 真实化：基于 {@link CustodyPolicy#getColdWalletPrefix()} 配置前缀判定
     * （替代迁移期硬编码 "cold" 前缀猜测），前缀可经策略配置调整。</p>
     *
     * @param walletId 钱包 ID
     * @return 是否为冷托管
     */
    @Override
    public boolean isColdCustody(String walletId) {
        if (walletId == null || walletId.isEmpty()) {
            return false;
        }
        String prefix = custodyPolicy != null && custodyPolicy.getColdWalletPrefix() != null
                ? custodyPolicy.getColdWalletPrefix() : "cold";
        return walletId.toLowerCase().startsWith(prefix);
    }

    /**
     * 查询指定钱包的托管层级。
     *
     * <p>#17 真实化：前缀由 {@link CustodyPolicy} 配置驱动（默认 cold/warm），
     * 消除硬编码猜测；未匹配任何配置前缀时默认 HOT。</p>
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
        String coldPrefix = custodyPolicy != null && custodyPolicy.getColdWalletPrefix() != null
                ? custodyPolicy.getColdWalletPrefix() : "cold";
        String warmPrefix = custodyPolicy != null && custodyPolicy.getWarmWalletPrefix() != null
                ? custodyPolicy.getWarmWalletPrefix() : "warm";
        if (lower.startsWith(coldPrefix)) {
            return WalletTier.COLD.name();
        } else if (lower.startsWith(warmPrefix)) {
            return WalletTier.WARM.name();
        }
        return WalletTier.HOT.name();
    }
}
