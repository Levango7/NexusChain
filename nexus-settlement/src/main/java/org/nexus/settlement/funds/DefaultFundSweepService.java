package org.nexus.settlement.funds;

import org.nexus.settlement.clearing.Ledger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *   <li>{@link #sweep}：单笔归集，校验后通过 {@link Ledger} 完成源地址 → 目标地址的转账落账，
 *       并推进订单状态 PENDING → SWEEPING → SETTLED / FAILED</li>
 *   <li>{@link #autoSweep}：扫描内部登记的待归集订单（PENDING）并批量执行</li>
 *   <li>{@link #transferToCold}：热钱包余额达到阈值时，整体转移至冷钱包并落账</li>
 * </ul>
 * 账户以地址 / 钱包名作为账本账户名；热、冷钱包为约定账户名
 * {@link #HOT_WALLET} / {@link #COLD_WALLET}。链上真实转账仍为 TODO，
 * 需在接入链上执行后补充。
 * </p>
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

    /** 已登记的归集订单（供 autoSweep 扫描） */
    private final List<CollectionOrder> orders = new CopyOnWriteArrayList<>();

    public DefaultFundSweepService(Ledger ledger) {
        this(ledger, DEFAULT_COLD_THRESHOLD);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DefaultFundSweepService(Ledger ledger,
                                   @org.springframework.beans.factory.annotation.Value("${nexus.settlement.cold-threshold:10000}") BigDecimal coldThreshold) {
        this.ledger = ledger;
        this.coldThreshold = coldThreshold != null ? coldThreshold : DEFAULT_COLD_THRESHOLD;
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
            // TODO: 链上真实转账（构造交易 → 广播 → 等待确认），接入链上执行后补充
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

    @Override
    public int transferToCold() {
        BigDecimal hotBalance = ledger.balanceOf(HOT_WALLET);
        if (hotBalance.compareTo(coldThreshold) < 0) {
            log.debug("transferToCold skipped: hotBalance={} < threshold={}", hotBalance, coldThreshold);
            return 0;
        }
        // TODO: 冷钱包转移需多签审批，接入审批流后补充
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
