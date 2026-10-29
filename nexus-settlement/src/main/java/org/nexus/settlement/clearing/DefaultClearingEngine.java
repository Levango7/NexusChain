package org.nexus.settlement.clearing;

import org.nexus.settlement.execution.OnChainExecutionChannel;
import org.nexus.settlement.execution.SandboxOnChainExecutionChannel;
import org.nexus.settlement.execution.TransactionRequest;
import org.nexus.settlement.execution.TransactionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 默认清结算引擎实现。
 * <p>
 * 账本级清结算：对批次内清算订单逐笔推进结算状态并汇总净额；
 * 每笔结算同时通过 {@link Ledger} 完成复式记账（借：待结算负债，贷：商户可用余额），
 * 并通过 {@link OnChainExecutionChannel} 发起链上结算转账。
 * </p>
 *
 * <p>链上执行通道通过 Spring 注入；当上层应用（nexus-gateway）提供真实实现时
 * 使用真实通道，否则 fallback 到 {@link SandboxOnChainExecutionChannel}。</p>
 */
@Service
public class DefaultClearingEngine implements ClearingEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultClearingEngine.class);

    /** 平台结算热钱包地址默认值 */
    private static final String DEFAULT_PLATFORM_SETTLEMENT_ADDRESS = "PLATFORM_HOT_WALLET";

    /** 账本组件 */
    private final Ledger ledger;

    /** 对账服务（可选注入，用于触发对账） */
    private final org.nexus.settlement.reconciliation.ReconciliationService reconciliationService;

    /** 链上执行通道 */
    private final OnChainExecutionChannel executionChannel;

    /** 平台结算热钱包链上地址 */
    private final String platformSettlementAddress;

    /** 链上记录数据源（可选注入：结算成功后向回填型数据源同步链上凭证） */
    private org.nexus.settlement.reconciliation.ChainRecordSource chainRecordSource;

    /** 清算订单仓储（可选注入：settle 终态回填落库，账务核心持久化） */
    private ClearingOrderRepository clearingOrderRepository;

    public DefaultClearingEngine(Ledger ledger,
                                 org.nexus.settlement.reconciliation.ReconciliationService reconciliationService) {
        this(ledger, reconciliationService, new SandboxOnChainExecutionChannel(),
                DEFAULT_PLATFORM_SETTLEMENT_ADDRESS);
    }

    @Autowired
    public DefaultClearingEngine(Ledger ledger,
                                 org.nexus.settlement.reconciliation.ReconciliationService reconciliationService,
                                 OnChainExecutionChannel executionChannel,
                                 @Value("${nexus.settlement.platform-address:PLATFORM_HOT_WALLET}") String platformSettlementAddress) {
        this.ledger = ledger;
        this.reconciliationService = reconciliationService;
        this.executionChannel = executionChannel;
        this.platformSettlementAddress = platformSettlementAddress != null && !platformSettlementAddress.isEmpty()
                ? platformSettlementAddress : DEFAULT_PLATFORM_SETTLEMENT_ADDRESS;
    }

    /**
     * 可选注入链上记录数据源（setter 注入，避免破坏既有构造器签名）。
     * 注入后：settle 成功时若数据源实现 {@link org.nexus.settlement.reconciliation.ChainRecordFeedable}，
     * 将链上凭证回填，形成「结算 → 链上记录 → 对账匹配」闭环，消除虚假差错。
     *
     * @param chainRecordSource 链上记录数据源（可为 null）
     */
    @Autowired(required = false)
    public void setChainRecordSource(
            org.nexus.settlement.reconciliation.ChainRecordSource chainRecordSource) {
        this.chainRecordSource = chainRecordSource;
    }

    /**
     * 可选注入清算订单仓储（setter 注入）。
     * 注入后：settle 成功时将终态（SETTLED + settlementTxHash）回写
     * {@code clearing_order} 表，跨批次/重启可追溯；
     * 未注入（纯单测）时跳过落库，原语义不变。
     *
     * @param clearingOrderRepository 清算订单仓储（可为 null）
     */
    @Autowired(required = false)
    public void setClearingOrderRepository(ClearingOrderRepository clearingOrderRepository) {
        this.clearingOrderRepository = clearingOrderRepository;
    }

    @Override
    public SettlementBatch batchClear(SettlementBatch batch) {
        if (batch == null) {
            return null;
        }
        List<ClearingOrder> orders = batch.getOrders() != null ? batch.getOrders() : List.of();
        if (orders.isEmpty()) {
            log.warn("batchClear received empty batch: batchNo={}", batch.getBatchNo());
            batch.setStatus(SettlementBatch.BatchStatus.FAILED);
            return batch;
        }

        BigDecimal netAmount = BigDecimal.ZERO;
        for (ClearingOrder order : orders) {
            ClearingOrder settled;
            try {
                settled = settle(order);
            } catch (RuntimeException e) {
                // 关键：drainStaging 已经把这批 PENDING 行删掉并提交了（见类注释）。
                // 单笔抛异常若不在此收口，batchClear 会整批中断、调度器只记日志，
                // 于是"账已贷、链未付、订单行也不存在"——记录静默丢失且永不重试。
                // 这里把异常单笔落为 FAILED 并持久化，其余单子继续结算（不改变记账顺序）。
                settled = order;
                settled.setStatus(ClearingOrder.OrderStatus.FAILED);
                log.error("settle threw unexpectedly, marking order FAILED to keep the audit trail "
                                + "(batch continues): orderId={}, error={}",
                        order.getOrderId(), e.toString(), e);
                persistOrderState(settled);
            }
            if (settled.getStatus() == ClearingOrder.OrderStatus.SETTLED && settled.getAmount() != null) {
                netAmount = netAmount.add(settled.getAmount());
            }
        }

        batch.setSettlementAmount(netAmount);
        batch.setStatus(SettlementBatch.BatchStatus.SETTLED);
        log.info("batchClear completed: batchNo={}, orders={}, netAmount={}",
                batch.getBatchNo(), orders.size(), netAmount);
        return batch;
    }

    @Override
    public ClearingOrder settle(ClearingOrder order) {
        if (order == null) {
            return null;
        }
        if (order.getStatus() == ClearingOrder.OrderStatus.SETTLED) {
            return order;
        }
        if (order.getAmount() == null || order.getMerchantId() == null) {
            order.setStatus(ClearingOrder.OrderStatus.FAILED);
            // 2026-10-30（S1）：本分支原先直接 return，不落库。drainStaging 在取出时已把
            // PENDING 行删除并提交（见类注释），不落库等于订单行静默丢失、审计链断裂。
            // 与下方各终态分支保持一致，补一次落库。
            persistOrderState(order);
            return order;
        }
        // 2026-10-30（S1，板 20261029-2352 / 20261029-2057 §裁决一）：
        // 收款地址预检**必须在贷账之前**。
        //
        // bookSettlement 内部走独立 TransactionTemplate 当场提交（见 Ledger），而 settle()
        // 本身无 @Transactional ⇒ 贷账一旦发生就不可回滚。若「地址不可用」在动链时才暴露，
        // 就会留下「已贷账、必失败」的永久偏移：FAILED 是终态、drainStaging 只捞 PENDING、
        // 全仓无冲正能力，三重叠加导致该偏移无法自动纠正。
        //
        // 同时本方法不再把 "MERCHANT:" + merchantId 当收款地址——那是合成账户名，不是链上
        // 地址；真实收款地址在 clearing_order.payee_address（由 SettlementEventCollector 写入）。
        String payeeAddress = order.getPayeeAddress();
        if (!executionChannel.canTransferTo(payeeAddress)) {
            order.setStatus(ClearingOrder.OrderStatus.FAILED);
            log.error("settle aborted BEFORE booking: payee address is missing or not a usable chain "
                            + "address (no ledger entry written, order will not be retried): "
                            + "orderId={}, merchantId={}, payeeAddress={}",
                    order.getOrderId(), order.getMerchantId(), payeeAddress);
            persistOrderState(order);
            return order;
        }
        // 账户级落账：借：待结算负债，贷：商户可用余额
        ledger.bookSettlement(order.getMerchantId(), order.getAmount(), order.getOrderId());
        // 链上结算转账：通过统一执行通道发起
        TransactionRequest request = new TransactionRequest(
                TransactionRequest.Type.SETTLEMENT,
                platformSettlementAddress,
                payeeAddress,
                order.getAmount(),
                order.getCurrency(),
                "settlement:" + order.getOrderId(),
                order.getOrderId());
        TransactionResult result = executionChannel.execute(request);
        if (result != null && result.isAwaitingApproval()) {
            // 大额结算转人工审批：钱未出。不能记 FAILED（审批通过后还会执行），
            // 也绝不能留在 PENDING（drainStaging 会重捞 PENDING 再跑一次 settle()，
            // 而上面的 bookSettlement 无去重 ⇒ 重复贷账）。状态名见 ClearingOrder.OrderStatus 注释。
            order.setStatus(ClearingOrder.OrderStatus.AWAITING);
            log.info("settle awaiting signing approval: orderId={}, approvalId={}, amount={}",
                    order.getOrderId(), result.getApprovalId(), order.getAmount());
            persistOrderState(order);
            return order;
        }
        if (result != null && result.isExecutedUnverified()) {
            // 执行结果不可核对：钱可能已出 —— 禁止重试，转人工核对，同样不记 FAILED
            order.setStatus(ClearingOrder.OrderStatus.UNVERIFIED);
            log.error("settle executed but unverified (manual check required, retry forbidden): "
                            + "orderId={}, approvalId={}, error={}",
                    order.getOrderId(), result.getApprovalId(), result.getError());
            persistOrderState(order);
            return order;
        }
        if (result != null && result.isSuccess()) {
            order.setStatus(ClearingOrder.OrderStatus.SETTLED);
            order.setSettlementTxHash(result.getTxHash());
            log.info("settle on-chain success: orderId={}, txHash={}, simulated={}",
                    order.getOrderId(), result.getTxHash(), result.isSimulated());
            // 链上凭证回填：向回填型数据源同步，供下一轮 reconcileWithChain 匹配
            feedChainRecordSource(order);
            // 终态落库：SETTLED + settlementTxHash 回写 clearing_order（账务核心持久化）
            persistOrderState(order);
        } else {
            order.setStatus(ClearingOrder.OrderStatus.FAILED);
            String err = result != null ? result.getError() : "execution channel returned null";
            log.error("settle on-chain failed: orderId={}, error={}", order.getOrderId(), err);
            // FAILED 终态同样落库（差错可追溯）
            persistOrderState(order);
        }
        return order;
    }

    /**
     * 审批通过后由推进器收口：把仍处于 {@link ClearingOrder.OrderStatus#AWAITING}
     * 的清算订单落为 SETTLED 并回填结算凭证。
     *
     * <p>本方法<b>不重跑 {@code settle()}</b>，因此不会二次 {@code bookSettlement}；
     * 状态不是 AWAITING 时不改写（幂等，且拒绝把已终态的单子拉回）。</p>
     */
    @Override
    public boolean completeAfterSigningApproval(String orderId, String settlementTxHash) {
        if (clearingOrderRepository == null) {
            log.error("待审批结算收口失败：清算订单仓储未装配, orderId={}", orderId);
            return false;
        }
        ClearingOrder order = loadAwaitingOrder(orderId);
        if (order == null) {
            return false;
        }
        if (settlementTxHash == null || settlementTxHash.isBlank()) {
            // 理论不可达（EXECUTED 必有哈希）；保守转人工核对，不落 SETTLED
            order.setStatus(ClearingOrder.OrderStatus.UNVERIFIED);
            persistOrderState(order);
            log.error("审批执行完成但无结算交易哈希，转 UNVERIFIED 待人工核对: orderId={}", orderId);
            return false;
        }
        order.setStatus(ClearingOrder.OrderStatus.SETTLED);
        order.setSettlementTxHash(settlementTxHash);
        feedChainRecordSource(order);
        persistOrderState(order);
        log.info("settle completed after signing approval: orderId={}, txHash={}", orderId, settlementTxHash);
        return true;
    }

    /** 审批被拒/过期（确定未动账）：AWAITING → FAILED。 */
    @Override
    public boolean markSigningApprovalRejected(String orderId, String reason) {
        if (clearingOrderRepository == null) {
            return false;
        }
        ClearingOrder order = loadAwaitingOrder(orderId);
        if (order == null) {
            return false;
        }
        order.setStatus(ClearingOrder.OrderStatus.FAILED);
        persistOrderState(order);
        log.warn("settle signing approval rejected（确定未动账）: orderId={}, reason={}", orderId, reason);
        return true;
    }

    /** 审批腿执行结果不可核对：AWAITING → UNVERIFIED（禁止重试，等人工）。 */
    @Override
    public boolean markSigningApprovalUnverified(String orderId, String reason) {
        if (clearingOrderRepository == null) {
            return false;
        }
        ClearingOrder order = loadAwaitingOrder(orderId);
        if (order == null) {
            return false;
        }
        order.setStatus(ClearingOrder.OrderStatus.UNVERIFIED);
        persistOrderState(order);
        log.error("settle signing approval executed but unverified, 转 UNVERIFIED（禁止自动重试）: "
                + "orderId={}, reason={}", orderId, reason);
        return true;
    }

    private ClearingOrder loadAwaitingOrder(String orderId) {
        ClearingOrder order = clearingOrderRepository.findById(orderId).orElse(null);
        if (order == null) {
            log.warn("清算订单不存在，无法按审批结果收口: orderId={}", orderId);
            return null;
        }
        if (order.getStatus() != ClearingOrder.OrderStatus.AWAITING) {
            log.info("清算订单已不在待审批状态，跳过收口: orderId={}, status={}", orderId, order.getStatus());
            return null;
        }
        return order;
    }

    /**
     * 回写清算订单仓储（终态 SETTLED/FAILED 与非终态 AWAITING/UNVERIFIED 都走这里）。
     * 仓储未注入（纯单测路径）或落库异常时跳过，不影响结算主流程；
     * 落库异常仅 WARN（账务数据以 ledger_entry 双写为准，本表用于状态追溯）。
     */
    private void persistOrderState(ClearingOrder order) {
        if (clearingOrderRepository == null) {
            return;
        }
        try {
            clearingOrderRepository.save(order);
        } catch (RuntimeException e) {
            log.warn("persist clearing order terminal state failed: orderId={}, error={}",
                    order.getOrderId(), e.getMessage());
        }
    }

    /**
     * 向回填型链上记录源同步结算凭证。
     * 数据源未注入或不支持回填（真实 RPC 实现）时静默跳过，
     * 不影响结算主流程。
     */
    private void feedChainRecordSource(ClearingOrder order) {
        if (chainRecordSource == null
                || !(chainRecordSource instanceof org.nexus.settlement.reconciliation.ChainRecordFeedable)) {
            return;
        }
        try {
            org.nexus.settlement.reconciliation.ChainRecordFeedable feedable =
                    (org.nexus.settlement.reconciliation.ChainRecordFeedable) chainRecordSource;
            feedable.feedSettlementRecord(new org.nexus.settlement.reconciliation.SettlementRecord(
                    order.getOrderId(), order.getAmount(), order.getCurrency(), java.time.Instant.now()));
        } catch (RuntimeException e) {
            // 回填失败不影响结算结果（真实数据源应从链上拉取，回填仅是内存实现的优化）
            log.warn("feed chain record source failed: orderId={}, error={}",
                    order.getOrderId(), e.getMessage());
        }
    }

    @Override
    public org.nexus.settlement.reconciliation.ReconciliationReport reconcile(
            org.nexus.settlement.reconciliation.ReconciliationReport report) {
        // 触发链上对账，产出真实比对报告
        org.nexus.settlement.reconciliation.ReconciliationReport chainReport =
                reconciliationService.reconcileWithChain();
        // 将链上对账差错并入输入报告
        if (chainReport != null && chainReport.getDiscrepancyCount() > 0) {
            java.util.List<String> merged = new java.util.ArrayList<>();
            if (report != null && report.getDiscrepancies() != null) {
                merged.addAll(report.getDiscrepancies());
            }
            merged.addAll(chainReport.getDiscrepancies());
            report = report != null ? report : new org.nexus.settlement.reconciliation.ReconciliationReport();
            report.setDiscrepancies(merged);
            report.setDiscrepancyCount(merged.size());
            report.setMatchedCount(chainReport.getMatchedCount());
            report.setReconcileDate(chainReport.getReconcileDate());
        }
        return reconciliationService.reportDiscrepancy(report != null ? report : chainReport);
    }
}
