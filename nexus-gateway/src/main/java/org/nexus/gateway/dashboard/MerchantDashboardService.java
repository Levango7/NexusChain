package org.nexus.gateway.dashboard;

import org.nexus.gateway.MerchantService;
import org.nexus.gateway.clearing.SettlementPeriod;
import org.nexus.gateway.model.PaymentOrder;
import org.nexus.gateway.orchestration.connector.ConnectorRegistry;
import org.nexus.gateway.orchestration.connector.PaymentConnector;
import org.nexus.gateway.repository.PaymentOrderRepository;
import org.nexus.gateway.risk.RiskEvent;
import org.nexus.gateway.risk.RiskEventRepository;
import org.nexus.gateway.settlement.MerchantSettlementConfig;
import org.nexus.gateway.settlement.MerchantSettlementConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 商户门户仪表盘服务。
 *
 * <p>聚合交易、结算、渠道分布、风控等多维度数据，为商户门户提供一站式概览。</p>
 *
 * <p>数据来源：
 * <ul>
 *   <li>{@link PaymentOrderRepository} — 交易汇总、近期交易</li>
 *   <li>{@link MerchantSettlementConfigRepository} — 结算周期配置</li>
 *   <li>{@link RiskEventRepository} — 风控事件统计</li>
 *   <li>{@link ConnectorRegistry} — 可用支付渠道列表</li>
 *   <li>{@link MerchantService} — 商户信息校验</li>
 * </ul></p>
 */
@Service
public class MerchantDashboardService {

    private static final Logger log = LoggerFactory.getLogger(MerchantDashboardService.class);

    /** 近期交易列表最大返回条数 */
    private static final int RECENT_TRANSACTION_LIMIT = 10;

    /** 高风险事件阈值（riskScore >= 此值视为高风险） */
    private static final int HIGH_RISK_THRESHOLD = 70;

    /** 风控拦截决策标识 */
    private static final String RISK_DECISION_REJECTED = "REJECTED";

    /** 默认结算周期（无配置时使用） */
    private static final SettlementPeriod DEFAULT_SETTLEMENT_PERIOD = SettlementPeriod.T1;

    private final PaymentOrderRepository paymentOrderRepository;
    private final RiskEventRepository riskEventRepository;
    private final MerchantService merchantService;
    private final ConnectorRegistry connectorRegistry;
    private final MerchantSettlementConfigRepository settlementConfigRepository;

    @Autowired
    public MerchantDashboardService(
            PaymentOrderRepository paymentOrderRepository,
            RiskEventRepository riskEventRepository,
            MerchantService merchantService,
            ConnectorRegistry connectorRegistry,
            MerchantSettlementConfigRepository settlementConfigRepository) {
        this.paymentOrderRepository = paymentOrderRepository;
        this.riskEventRepository = riskEventRepository;
        this.merchantService = merchantService;
        this.connectorRegistry = connectorRegistry;
        this.settlementConfigRepository = settlementConfigRepository;
    }

    // ==================== 核心方法 ====================

    /**
     * 获取商户完整仪表盘数据。
     *
     * <p>当商户不存在时，返回各字段为零值/空列表的空 DashboardData，
     * 不抛出异常，确保前端可正常渲染空状态页面。</p>
     *
     * @param merchantId 商户 ID
     * @return 仪表盘数据（商户不存在时返回空数据）
     */
    public DashboardData getDashboard(Long merchantId) {
        log.debug("Building dashboard for merchantId={}", merchantId);

        // 商户不存在时返回空数据
        if (merchantService.findById(merchantId).isEmpty()) {
            log.warn("Merchant not found: {}, returning empty dashboard", merchantId);
            return DashboardData.empty();
        }

        return new DashboardData(
                getTransactionSummary(merchantId),
                getSettlementSummary(merchantId),
                getRecentTransactions(merchantId),
                getConnectorDistribution(merchantId),
                getRiskSummary(merchantId)
        );
    }

    // ==================== 辅助方法 ====================

    /**
     * 聚合交易数据：总额、笔数、成功率、各状态计数。
     *
     * @param merchantId 商户 ID
     * @return 交易汇总
     */
    public TransactionSummary getTransactionSummary(Long merchantId) {
        List<PaymentOrder> orders = paymentOrderRepository.findByMerchantId(merchantId);

        if (orders.isEmpty()) {
            return TransactionSummary.zero();
        }

        BigDecimal totalVolume = BigDecimal.ZERO;
        int totalCount = orders.size();
        int successCount = 0;
        int pendingCount = 0;
        int failedCount = 0;
        int refundedCount = 0;

        for (PaymentOrder order : orders) {
            PaymentOrder.OrderStatus status = order.getStatus();
            switch (status) {
                case PAID -> {
                    successCount++;
                    if (order.getAmount() != null) {
                        totalVolume = totalVolume.add(order.getAmount());
                    }
                }
                case PENDING, PAYING, SUBMITTED -> pendingCount++;
                case FAILED, EXPIRED -> failedCount++;
                case REFUNDED -> refundedCount++;
                default -> { // REORGED, REFUND_PENDING 等其他状态不归类
                }
            }
        }

        BigDecimal successRate = totalCount > 0
                ? BigDecimal.valueOf(successCount)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(totalCount), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return new TransactionSummary(
                totalVolume,
                totalCount,
                successCount,
                successRate,
                pendingCount,
                failedCount,
                refundedCount
        );
    }

    /**
     * 聚合结算数据：待结算金额、已结算金额、结算周期。
     *
     * <p>待结算金额 = PAID 状态订单金额之和（尚未完成结算的）。
     * 已结算金额 = REFUNDED 状态订单金额之和（已退款即已完成结算生命周期）。
     * 结算周期从 {@link MerchantSettlementConfig} 获取，无配置默认 T1。</p>
     *
     * @param merchantId 商户 ID
     * @return 结算汇总
     */
    public SettlementSummary getSettlementSummary(Long merchantId) {
        List<PaymentOrder> paidOrders = paymentOrderRepository
                .findByMerchantIdAndStatus(merchantId, PaymentOrder.OrderStatus.PAID);

        BigDecimal pendingSettlementAmount = BigDecimal.ZERO;
        for (PaymentOrder order : paidOrders) {
            if (order.getAmount() != null) {
                pendingSettlementAmount = pendingSettlementAmount.add(order.getAmount());
            }
        }

        List<PaymentOrder> refundedOrders = paymentOrderRepository
                .findByMerchantIdAndStatus(merchantId, PaymentOrder.OrderStatus.REFUNDED);

        BigDecimal settledAmount = BigDecimal.ZERO;
        for (PaymentOrder order : refundedOrders) {
            if (order.getAmount() != null) {
                settledAmount = settledAmount.add(order.getAmount());
            }
        }

        String settlementPeriod = resolveSettlementPeriod(merchantId);

        return new SettlementSummary(
                pendingSettlementAmount,
                settledAmount,
                settlementPeriod
        );
    }

    /**
     * 查询近期交易列表（最近 10 笔，按 createdAt 降序）。
     *
     * @param merchantId 商户 ID
     * @return 近期交易列表（最多 10 条）
     */
    public List<RecentTransaction> getRecentTransactions(Long merchantId) {
        List<PaymentOrder> orders = paymentOrderRepository.findByMerchantId(merchantId);

        return orders.stream()
                .sorted(Comparator.comparing(PaymentOrder::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(RECENT_TRANSACTION_LIMIT)
                .map(order -> new RecentTransaction(
                        order.getOrderNo(),
                        order.getAmount(),
                        order.getStatus() != null ? order.getStatus().name() : null,
                        null, // PaymentOrder 无 connectorId 字段
                        order.getCreatedAt()
                ))
                .collect(Collectors.toList());
    }

    /**
     * 统计渠道分布：各 connector 的基本信息。
     *
     * <p>PaymentOrder 无 connectorId 字段，因此从 {@link ConnectorRegistry}
     * 获取可用渠道列表，展示各渠道的 id、displayName、type、active 状态。
     * transactionCount 和 amountPercentage 暂为 0（PaymentOrder 不记录渠道归属）。</p>
     *
     * @param merchantId 商户 ID
     * @return 渠道分布列表
     */
    public List<ConnectorDistribution> getConnectorDistribution(Long merchantId) {
        Collection<PaymentConnector> connectors = connectorRegistry.getAll();

        return connectors.stream()
                .map(connector -> new ConnectorDistribution(
                        connector.getId(),
                        connector.getDisplayName(),
                        connector.getType(),
                        connector.isActive(),
                        0, // PaymentOrder 无 connectorId，无法统计交易笔数
                        BigDecimal.ZERO // 无法统计金额占比
                ))
                .collect(Collectors.toList());
    }

    /**
     * 聚合风控数据：风控事件总数、高风险事件数、被拦截交易数。
     *
     * <p>高风险事件 = riskScore >= {@value #HIGH_RISK_THRESHOLD} 的事件。
     * 被拦截交易 = riskDecision 为 "REJECTED" 的事件。</p>
     *
     * @param merchantId 商户 ID
     * @return 风控摘要
     */
    public RiskSummary getRiskSummary(Long merchantId) {
        List<RiskEvent> events = riskEventRepository
                .findByMerchantIdOrderByOccurredAtDesc(merchantId);

        if (events.isEmpty()) {
            return RiskSummary.zero();
        }

        int totalRiskEvents = events.size();
        int highRiskEvents = 0;
        int blockedTransactions = 0;

        for (RiskEvent event : events) {
            if (event.getRiskScore() != null && event.getRiskScore() >= HIGH_RISK_THRESHOLD) {
                highRiskEvents++;
            }
            if (RISK_DECISION_REJECTED.equals(event.getRiskDecision())) {
                blockedTransactions++;
            }
        }

        return new RiskSummary(totalRiskEvents, highRiskEvents, blockedTransactions);
    }

    // ==================== 私有方法 ====================

    /**
     * 解析商户结算周期字符串。
     *
     * <p>从 {@link MerchantSettlementConfigRepository} 查询配置，
     * 无配置时默认 T1。CUSTOM 模式返回 "CUSTOM_{customDays}"。</p>
     *
     * @param merchantId 商户 ID
     * @return 结算周期字符串
     */
    private String resolveSettlementPeriod(Long merchantId) {
        Optional<MerchantSettlementConfig> configOpt = settlementConfigRepository
                .findByMerchantId(merchantId);

        if (configOpt.isEmpty()) {
            return DEFAULT_SETTLEMENT_PERIOD.name();
        }

        MerchantSettlementConfig config = configOpt.get();
        if (config.getSettlementPeriod() == SettlementPeriod.CUSTOM) {
            Integer customDays = config.getCustomDays();
            if (customDays != null) {
                return "CUSTOM_" + customDays;
            }
            return DEFAULT_SETTLEMENT_PERIOD.name();
        }

        return config.getSettlementPeriod().name();
    }

    // ==================== 数据类定义 ====================

    /**
     * 仪表盘完整数据。
     */
    public static class DashboardData {
        private final TransactionSummary transactionSummary;
        private final SettlementSummary settlementSummary;
        private final List<RecentTransaction> recentTransactions;
        private final List<ConnectorDistribution> connectorDistribution;
        private final RiskSummary riskSummary;

        public DashboardData(
                TransactionSummary transactionSummary,
                SettlementSummary settlementSummary,
                List<RecentTransaction> recentTransactions,
                List<ConnectorDistribution> connectorDistribution,
                RiskSummary riskSummary) {
            this.transactionSummary = transactionSummary;
            this.settlementSummary = settlementSummary;
            this.recentTransactions = recentTransactions;
            this.connectorDistribution = connectorDistribution;
            this.riskSummary = riskSummary;
        }

        /** 创建各字段为零值/空列表的空 DashboardData */
        public static DashboardData empty() {
            return new DashboardData(
                    TransactionSummary.zero(),
                    SettlementSummary.zero(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    RiskSummary.zero()
            );
        }

        public TransactionSummary getTransactionSummary() { return transactionSummary; }
        public SettlementSummary getSettlementSummary() { return settlementSummary; }
        public List<RecentTransaction> getRecentTransactions() { return recentTransactions; }
        public List<ConnectorDistribution> getConnectorDistribution() { return connectorDistribution; }
        public RiskSummary getRiskSummary() { return riskSummary; }
    }

    /**
     * 交易汇总数据。
     */
    public static class TransactionSummary {
        private final BigDecimal totalVolume;
        private final int totalCount;
        private final int successCount;
        private final BigDecimal successRate;
        private final int pendingCount;
        private final int failedCount;
        private final int refundedCount;

        public TransactionSummary(
                BigDecimal totalVolume,
                int totalCount,
                int successCount,
                BigDecimal successRate,
                int pendingCount,
                int failedCount,
                int refundedCount) {
            this.totalVolume = totalVolume;
            this.totalCount = totalCount;
            this.successCount = successCount;
            this.successRate = successRate;
            this.pendingCount = pendingCount;
            this.failedCount = failedCount;
            this.refundedCount = refundedCount;
        }

        public static TransactionSummary zero() {
            return new TransactionSummary(
                    BigDecimal.ZERO, 0, 0, BigDecimal.ZERO, 0, 0, 0
            );
        }

        public BigDecimal getTotalVolume() { return totalVolume; }
        public int getTotalCount() { return totalCount; }
        public int getSuccessCount() { return successCount; }
        public BigDecimal getSuccessRate() { return successRate; }
        public int getPendingCount() { return pendingCount; }
        public int getFailedCount() { return failedCount; }
        public int getRefundedCount() { return refundedCount; }
    }

    /**
     * 结算汇总数据。
     */
    public static class SettlementSummary {
        private final BigDecimal pendingSettlementAmount;
        private final BigDecimal settledAmount;
        private final String settlementPeriod;

        public SettlementSummary(
                BigDecimal pendingSettlementAmount,
                BigDecimal settledAmount,
                String settlementPeriod) {
            this.pendingSettlementAmount = pendingSettlementAmount;
            this.settledAmount = settledAmount;
            this.settlementPeriod = settlementPeriod;
        }

        public static SettlementSummary zero() {
            return new SettlementSummary(
                    BigDecimal.ZERO, BigDecimal.ZERO, SettlementPeriod.T1.name()
            );
        }

        public BigDecimal getPendingSettlementAmount() { return pendingSettlementAmount; }
        public BigDecimal getSettledAmount() { return settledAmount; }
        public String getSettlementPeriod() { return settlementPeriod; }
    }

    /**
     * 近期交易记录。
     */
    public static class RecentTransaction {
        private final String orderNo;
        private final BigDecimal amount;
        private final String status;
        private final String connectorId;
        private final LocalDateTime createdAt;

        public RecentTransaction(
                String orderNo,
                BigDecimal amount,
                String status,
                String connectorId,
                LocalDateTime createdAt) {
            this.orderNo = orderNo;
            this.amount = amount;
            this.status = status;
            this.connectorId = connectorId;
            this.createdAt = createdAt;
        }

        public String getOrderNo() { return orderNo; }
        public BigDecimal getAmount() { return amount; }
        public String getStatus() { return status; }
        public String getConnectorId() { return connectorId; }
        public LocalDateTime getCreatedAt() { return createdAt; }
    }

    /**
     * 渠道分布数据。
     */
    public static class ConnectorDistribution {
        private final String connectorId;
        private final String displayName;
        private final String type;
        private final boolean active;
        private final int transactionCount;
        private final BigDecimal amountPercentage;

        public ConnectorDistribution(
                String connectorId,
                String displayName,
                String type,
                boolean active,
                int transactionCount,
                BigDecimal amountPercentage) {
            this.connectorId = connectorId;
            this.displayName = displayName;
            this.type = type;
            this.active = active;
            this.transactionCount = transactionCount;
            this.amountPercentage = amountPercentage;
        }

        public String getConnectorId() { return connectorId; }
        public String getDisplayName() { return displayName; }
        public String getType() { return type; }
        public boolean isActive() { return active; }
        public int getTransactionCount() { return transactionCount; }
        public BigDecimal getAmountPercentage() { return amountPercentage; }
    }

    /**
     * 风控摘要数据。
     */
    public static class RiskSummary {
        private final int totalRiskEvents;
        private final int highRiskEvents;
        private final int blockedTransactions;

        public RiskSummary(
                int totalRiskEvents,
                int highRiskEvents,
                int blockedTransactions) {
            this.totalRiskEvents = totalRiskEvents;
            this.highRiskEvents = highRiskEvents;
            this.blockedTransactions = blockedTransactions;
        }

        public static RiskSummary zero() {
            return new RiskSummary(0, 0, 0);
        }

        public int getTotalRiskEvents() { return totalRiskEvents; }
        public int getHighRiskEvents() { return highRiskEvents; }
        public int getBlockedTransactions() { return blockedTransactions; }
    }
}