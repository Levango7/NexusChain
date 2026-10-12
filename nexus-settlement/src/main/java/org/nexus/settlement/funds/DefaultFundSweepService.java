package org.nexus.settlement.funds;

import org.nexus.settlement.clearing.Ledger;
import org.nexus.settlement.execution.OnChainExecutionChannel;
import org.nexus.settlement.execution.SandboxOnChainExecutionChannel;
import org.nexus.settlement.execution.TransactionRequest;
import org.nexus.settlement.execution.TransactionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 默认资金归集服务实现。
 * <p>
 * 归集流程：
 * <ul>
 *   <li>{@link #sweep}：单笔归集，校验后通过 {@link OnChainExecutionChannel} 发起链上转账，
 *       成功后通过 {@link Ledger} 完成源地址 → 目标地址的转账落账，
 *       并推进订单状态 PENDING → SWEEPING → SETTLED / FAILED</li>
 *   <li>{@link #autoSweep}：扫描内部登记的待归集订单（PENDING）并批量执行</li>
 *   <li>{@link #transferToCold}：热钱包余额达到阈值时，整体转移至冷钱包并落账</li>
 * </ul>
 * 账户以地址 / 钱包名作为账本账户名；热、冷钱包为约定账户名
 * {@link #HOT_WALLET} / {@link #COLD_WALLET}。
 * </p>
 *
 * <p>链上执行通道通过 Spring 注入；当上层应用（nexus-gateway）提供真实实现时
 * 使用真实通道，否则 fallback 到 {@link SandboxOnChainExecutionChannel}。</p>
 */
@Service
public class DefaultFundSweepService implements FundSweepService {

    private static final Logger log = LoggerFactory.getLogger(DefaultFundSweepService.class);

    /** 热钱包账户名 */
    public static final String HOT_WALLET = "HOT_WALLET";

    /** 冷钱包账户名 */
    public static final String COLD_WALLET = "COLD_WALLET";

    /** 冷钱包转移默认阈值 */
    private static final BigDecimal DEFAULT_COLD_THRESHOLD = new BigDecimal("10000");

    private final Ledger ledger;
    private final BigDecimal coldThreshold;
    private final OnChainExecutionChannel executionChannel;

    /**
     * 多签审批服务（P0 修复 2026-10-11，可空）。
     *
     * <p>{@code null} = 旧装配/单测（保持历史行为）；Spring 运行时恒注入 ⇒
     * 生产的热→冷转移**必须**携带已批准的 approvalId。</p>
     */
    private final MultiSigApprovalService multiSigApprovalService;

    /** 冷钱包转移的审批用途标识（审批单 orderId 必须等于此值，防挪用其他审批）。 */
    public static final String COLD_TRANSFER_APPROVAL_SCOPE = "COLD_TRANSFER";

    /** 已登记的归集订单（供 autoSweep 扫描） */
    private final List<CollectionOrder> orders = new CopyOnWriteArrayList<>();

    public DefaultFundSweepService(Ledger ledger) {
        this(ledger, DEFAULT_COLD_THRESHOLD, new SandboxOnChainExecutionChannel());
    }


    public DefaultFundSweepService(Ledger ledger,
                                   @org.springframework.beans.factory.annotation.Value("${nexus.settlement.cold-threshold:10000}") BigDecimal coldThreshold) {
        this(ledger, coldThreshold, new SandboxOnChainExecutionChannel());
    }

    /** 测试便捷构造：无审批服务（保持历史行为）。 */
    public DefaultFundSweepService(Ledger ledger,
                                   @org.springframework.beans.factory.annotation.Value("${nexus.settlement.cold-threshold:10000}") BigDecimal coldThreshold,
                                   OnChainExecutionChannel executionChannel) {
        this(ledger, coldThreshold, executionChannel, null);
    }

    @Autowired
    public DefaultFundSweepService(Ledger ledger,
                                   @org.springframework.beans.factory.annotation.Value("${nexus.settlement.cold-threshold:10000}") BigDecimal coldThreshold,
                                   OnChainExecutionChannel executionChannel,
                                   MultiSigApprovalService multiSigApprovalService) {
        this.ledger = ledger;
        this.coldThreshold = coldThreshold != null ? coldThreshold : DEFAULT_COLD_THRESHOLD;
        this.executionChannel = executionChannel;
        this.multiSigApprovalService = multiSigApprovalService;
    }

    /**
     * 登记一笔待归集订单（供 autoSweep 扫描，测试 / 事件回放用）。
     *
     * @param order 归集订单
     */
    public void enqueue(CollectionOrder order) {
        if (order == null) {
            return;
        }
        if (order.getCreatedAt() == null) {
            order.setCreatedAt(Instant.now());
        }
        if (order.getStatus() == null) {
            order.setStatus(CollectionOrder.OrderStatus.PENDING);
        }
        orders.add(order);
    }

    @Override
    public CollectionOrder sweep(CollectionOrder order) {
        if (order == null) {
            return null;
        }
        if (order.getStatus() == CollectionOrder.OrderStatus.SETTLED) {
            return order;
        }
        if (!isValid(order)) {
            order.setStatus(CollectionOrder.OrderStatus.FAILED);
            log.warn("sweep rejected invalid order: orderId={}", order.getOrderId());
            return order;
        }

        order.setStatus(CollectionOrder.OrderStatus.SWEEPING);
        try {
            // 链上归集转账：通过统一执行通道发起
            TransactionRequest request = new TransactionRequest(
                    TransactionRequest.Type.SWEEP,
                    order.getSourceAddress(),
                    order.getTargetAddress(),
                    order.getAmount(),
                    order.getCurrency(),
                    "sweep:" + order.getOrderId(),
                    order.getOrderId());
            TransactionResult result = executionChannel.execute(request);
            if (result == null || !result.isSuccess()) {
                order.setStatus(CollectionOrder.OrderStatus.FAILED);
                String err = result != null ? result.getError() : "execution channel returned null";
                log.error("sweep on-chain failed: orderId={}, error={}", order.getOrderId(), err);
                return order;
            }
            log.info("sweep on-chain success: orderId={}, txHash={}, simulated={}",
                    order.getOrderId(), result.getTxHash(), result.isSimulated());
            // 链上成功后落账
            ledger.bookTransfer(order.getSourceAddress(), order.getTargetAddress(),
                    order.getAmount(), order.getOrderId());
            order.setStatus(CollectionOrder.OrderStatus.SETTLED);
            log.info("sweep settled: orderId={}, {} -> {}, amount={}",
                    order.getOrderId(), order.getSourceAddress(),
                    order.getTargetAddress(), order.getAmount());
        } catch (Exception e) {
            order.setStatus(CollectionOrder.OrderStatus.FAILED);
            log.error("sweep failed: orderId={}", order.getOrderId(), e);
        }
        return order;
    }

    @Override
    public int autoSweep() {
        int settled = 0;
        for (CollectionOrder order : orders) {
            if (order.getStatus() == CollectionOrder.OrderStatus.PENDING) {
                CollectionOrder swept = sweep(order);
                if (swept != null && swept.getStatus() == CollectionOrder.OrderStatus.SETTLED) {
                    settled++;
                }
            }
        }
        log.info("autoSweep completed: settled={}", settled);
        return settled;
    }

    /**
     * 无审批面的热→冷转移（P0 修复 2026-10-11：**默认拒绝**）。
     *
     * <p>此前本方法带 {@code TODO(v2.0.0): 冷钱包转移需多签审批} 并**直接转账** ——
     * 即"热钱包资金可被无审批地转出"。现：装配了审批服务（生产）时直接拒绝，
     * 强制调用方走 {@link #transferToCold(String)} 并携带已批准的审批单。</p>
     *
     * <p>{@code multiSigApprovalService == null}（旧装配/单测）时保持历史行为。</p>
     */
    @Override
    public int transferToCold() {
        if (multiSigApprovalService != null) {
            throw new IllegalStateException(
                    "冷钱包转移需多签审批（fail-closed）：请先经 MultiSigApprovalService 申请并审批"
                            + "（orderId=" + COLD_TRANSFER_APPROVAL_SCOPE
                            + "），再调用 transferToCold(approvalId)");
        }
        return doTransfer();
    }

    /**
     * 多签审批版热→冷转移（P0 修复 2026-10-11）。
     *
     * <p>两道校验：① 审批单状态 APPROVED；② 审批用途（orderId）必须是
     * {@link #COLD_TRANSFER_APPROVAL_SCOPE} —— 防止"拿一笔无关的审批去挪冷钱包资金"。</p>
     */
    @Override
    public int transferToCold(String approvalId) {
        if (multiSigApprovalService == null) {
            throw new IllegalStateException(
                    "冷钱包转移拒绝执行：多签审批服务未装配（fail-closed，不静默跳过审批）");
        }
        if (approvalId == null || approvalId.isBlank()) {
            throw new IllegalStateException("冷钱包转移拒绝执行：缺少 approvalId");
        }
        if (!multiSigApprovalService.isApproved(approvalId)) {
            throw new IllegalStateException(
                    "冷钱包转移拒绝执行：审批未通过或不存在 approvalId=" + approvalId);
        }
        MultiSigApprovalService.ApprovalRequest approval =
                multiSigApprovalService.getStatus(approvalId);
        if (approval == null || !COLD_TRANSFER_APPROVAL_SCOPE.equals(approval.getOrderId())) {
            throw new IllegalStateException(
                    "冷钱包转移拒绝执行：审批用途不符（须 orderId=" + COLD_TRANSFER_APPROVAL_SCOPE
                            + ", 实际=" + (approval == null ? "null" : approval.getOrderId()) + "）");
        }
        log.info("transferToCold approved: approvalId={}", approvalId);
        return doTransfer();
    }

    /** 实际转账（两条入口共用；未达阈值返回 0）。 */
    private int doTransfer() {
        BigDecimal hotBalance = ledger.balanceOf(HOT_WALLET);
        if (hotBalance.compareTo(coldThreshold) < 0) {
            log.debug("transferToCold skipped: hotBalance={} < threshold={}", hotBalance, coldThreshold);
            return 0;
        }
        ledger.bookTransfer(HOT_WALLET, COLD_WALLET, hotBalance, "COLD_TRANSFER");
        log.info("transferToCold completed: amount={}", hotBalance);
        return 1;
    }

    private boolean isValid(CollectionOrder order) {
        return order.getOrderId() != null
                && order.getSourceAddress() != null && !order.getSourceAddress().isEmpty()
                && order.getTargetAddress() != null && !order.getTargetAddress().isEmpty()
                && order.getAmount() != null && order.getAmount().compareTo(BigDecimal.ZERO) > 0;
    }
}
