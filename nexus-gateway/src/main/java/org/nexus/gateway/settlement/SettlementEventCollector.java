package org.nexus.gateway.settlement;

import org.nexus.analytics.event.PaymentCompletedEvent;
import org.nexus.settlement.clearing.ClearingOrder;
import org.nexus.settlement.clearing.ClearingOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 结算事件采集器（Gateway → Settlement 数据桥，账务核心持久化）。
 *
 * <p>监听 {@link PaymentCompletedEvent}（由 OrchestrationService 支付成功后发布），
 * 将支付事件映射为 {@link ClearingOrder}（PENDING 状态）。</p>
 *
 * <p>持久化设计（双模式）：
 * <ul>
 *   <li><b>DB 模式</b>：{@code @Autowired(required=false)} 注入
 *       {@link ClearingOrderRepository}，PENDING 订单落库 {@code clearing_order} 表；
 *       {@link #drainStaging()} 查询 PENDING 批次并删除（复刻取出+清空语义）。
 *       重启不丢，调度器跨实例可见。</li>
 *   <li><b>内存模式</b>：无 repository 时保留原 staging 列表语义，
 *       供纯单元测试 {@code new SettlementEventCollector()} 使用，零破坏。</li>
 * </ul></p>
 *
 * <p>放置于 nexus-gateway 组合根的原因：nexus-settlement 是独立 composite build，
 * 不依赖 nexus-analytics（事件类所在模块）；而 gateway 同时依赖两者，
 * 是天然的事件桥接层。与 nexus-analytics 自身的 PaymentEventCollector
 * 监听同一事件，各自独立处理，互不干扰。</p>
 */
@Component
public class SettlementEventCollector {

    private static final Logger log = LoggerFactory.getLogger(SettlementEventCollector.class);

    /** 默认结算周期（T+0，可按商户/产品线配置扩展） */
    private static final String DEFAULT_SETTLEMENT_CYCLE = "T0";

    /** 内存模式暂存（DB 模式下不使用） */
    private final List<ClearingOrder> stagingOrders = new CopyOnWriteArrayList<>();

    /** 清算订单仓储（null 则走内存模式） */
    private final ClearingOrderRepository clearingOrderRepository;

    /** 纯内存构造器（既有测试 new SettlementEventCollector() 走此路径） */
    public SettlementEventCollector() {
        this(null);
    }

    /**
     * 持久化构造器。repository 由 Spring 容器提供；
     * {@code required=false} 保证无 JPA 环境的装配不失败。
     *
     * @param clearingOrderRepository 清算订单仓储（null 时回退内存模式）
     */
    @Autowired
    public SettlementEventCollector(@Autowired(required = false) ClearingOrderRepository clearingOrderRepository) {
        this.clearingOrderRepository = clearingOrderRepository;
    }

    /**
     * 监听支付完成事件，映射为 ClearingOrder 并暂存（内存）或落库（DB）。
     *
     * @param event 支付完成事件
     */
    @EventListener
    public void onPaymentCompleted(PaymentCompletedEvent event) {
        if (event == null) {
            return;
        }
        ClearingOrder order = mapToClearingOrder(event);
        if (clearingOrderRepository != null) {
            clearingOrderRepository.save(order);
        } else {
            stagingOrders.add(order);
        }
        log.info("Settlement event collected: orderId={}, paymentId={}, merchantId={}, amount={}, currency={}, latencyMs={}, costBps={}, persisted={}",
                order.getOrderId(), order.getPaymentId(), order.getMerchantId(),
                order.getAmount(), order.getCurrency(),
                order.getRoutingLatencyMs(), order.getCostBps(),
                clearingOrderRepository != null);
    }

    /**
     * 取出并清空当前所有 PENDING 订单（供调度器定时调用）。
     *
     * <p>DB 模式：查询全部 PENDING 后删除（取出即清空，语义与内存模式一致）；
     * 内存模式：快照 + 清空 staging 列表。</p>
     *
     * @return 当前 PENDING 订单（调用后不再可见）
     */
    public List<ClearingOrder> drainStaging() {
        if (clearingOrderRepository != null) {
            List<ClearingOrder> pending =
                    clearingOrderRepository.findByStatus(ClearingOrder.OrderStatus.PENDING);
            clearingOrderRepository.deleteAll(pending);
            return pending;
        }
        List<ClearingOrder> drained = List.copyOf(stagingOrders);
        stagingOrders.clear();
        return drained;
    }

    /**
     * 查看当前 PENDING 订单数量（监控用，不清空）。
     *
     * @return PENDING 订单数
     */
    public int stagingSize() {
        if (clearingOrderRepository != null) {
            return clearingOrderRepository.findByStatus(ClearingOrder.OrderStatus.PENDING).size();
        }
        return stagingOrders.size();
    }

    /**
     * 事件 → 清算订单映射（包私有，供单元测试直接验证映射逻辑）。
     */
    ClearingOrder mapToClearingOrder(PaymentCompletedEvent event) {
        ClearingOrder order = new ClearingOrder();
        order.setOrderId("clr_" + UUID.randomUUID());
        order.setPaymentId(event.getPaymentId());
        order.setMerchantId(event.getMerchantId() != null ? event.getMerchantId().toString() : null);
        order.setAmount(event.getAmount());
        order.setCurrency(event.getCurrency());
        order.setChainTxHash(event.getChainTxHash());
        order.setConnectorId(event.getConnector());
        order.setRoutingLatencyMs(event.getLatencyMs());
        order.setCostBps(event.getCostBps());
        order.setPayerAddress(event.getPayerAddress());
        order.setPayeeAddress(event.getPayeeAddress());
        order.setSettlementCycle(DEFAULT_SETTLEMENT_CYCLE);
        order.setStatus(ClearingOrder.OrderStatus.PENDING);
        order.setCreatedAt(event.getOccurredAt() != null ? event.getOccurredAt() : Instant.now());
        return order;
    }
}