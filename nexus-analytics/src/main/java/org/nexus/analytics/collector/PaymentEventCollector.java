package org.nexus.analytics.collector;

import org.nexus.analytics.event.PaymentCompletedEvent;
import org.nexus.analytics.onchain.InMemoryTransactionDataSource;
import org.nexus.analytics.onchain.OnChainTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;

/**
 * 支付事件采集器。
 *
 * <p>监听 nexus-gateway 发布的 {@link PaymentCompletedEvent}，将交易数据写入
 * {@link InMemoryTransactionDataSource}，供 {@code TransactionGraphService}
 * 构建链上交易图谱、统计与导出消费。
 *
 * <p>设计要点：
 * <ul>
 *   <li>{@code @Async}：事件处理异步执行，不阻塞 gateway 支付主链路</li>
 *   <li>{@code @EventListener}：Spring 事件机制，gateway 仅依赖本模块的事件类即可发布</li>
 *   <li>金额转换：{@code BigDecimal} → {@code BigInteger}（取最小单位整数部分）</li>
 * </ul>
 */
@Component
public class PaymentEventCollector {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventCollector.class);

    private final InMemoryTransactionDataSource transactionDataSource;

    public PaymentEventCollector(InMemoryTransactionDataSource transactionDataSource) {
        this.transactionDataSource = transactionDataSource;
    }

    /**
     * 处理支付完成事件，将交易写入图谱数据源。
     *
     * @param event 支付完成事件
     */
    @Async
    @EventListener
    public void onPaymentCompleted(PaymentCompletedEvent event) {
        if (event == null) {
            return;
        }
        try {
            OnChainTransaction tx = mapToOnChainTransaction(event);
            transactionDataSource.feed(List.of(tx));
            log.info("Payment event collected: paymentId={}, txHash={}, merchantId={}, amount={}",
                    event.getPaymentId(), event.getChainTxHash(), event.getMerchantId(), event.getAmount());
        } catch (Exception e) {
            // 采集失败不影响主链路；记录错误后吞掉异常
            log.error("Failed to collect payment event: paymentId={}, error={}",
                    event.getPaymentId(), e.getMessage(), e);
        }
    }

    /**
     * 将支付事件映射为链上交易记录。
     */
    private OnChainTransaction mapToOnChainTransaction(PaymentCompletedEvent event) {
        Instant timestamp = event.getOccurredAt() != null ? event.getOccurredAt() : Instant.now();
        BigInteger amountInt = toBigInteger(event.getAmount());
        String merchantIdStr = event.getMerchantId() != null ? String.valueOf(event.getMerchantId()) : null;

        return OnChainTransaction.builder()
                .txHash(event.getChainTxHash())
                .fromAddress(event.getPayerAddress())
                .toAddress(event.getPayeeAddress())
                .amount(amountInt)
                .timestamp(timestamp)
                .status(OnChainTransaction.Status.SUCCESS)
                .merchantId(merchantIdStr)
                .build();
    }

    /**
     * BigDecimal → BigInteger 转换：取整数部分，null 安全。
     */
    private BigInteger toBigInteger(BigDecimal value) {
        if (value == null) {
            return BigInteger.ZERO;
        }
        return value.toBigInteger();
    }
}