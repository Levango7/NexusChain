package org.nexus.analytics.collector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.analytics.event.PaymentCompletedEvent;
import org.nexus.analytics.onchain.InMemoryTransactionDataSource;
import org.nexus.analytics.onchain.OnChainTransaction;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PaymentEventCollector} 单元测试。
 *
 * <p>覆盖事件采集、金额转换、空值安全与异常吞没等分支。
 */
class PaymentEventCollectorTest {

    private InMemoryTransactionDataSource dataSource;
    private PaymentEventCollector collector;

    @BeforeEach
    void setUp() {
        dataSource = new InMemoryTransactionDataSource();
        collector = new PaymentEventCollector(dataSource);
    }

    private PaymentCompletedEvent event(Long paymentId, BigDecimal amount, Long merchantId,
                                        String chainTxHash, String payer, String payee, Instant occurredAt) {
        return new PaymentCompletedEvent(
                this, paymentId, amount, "CNY", "ETHEREUM",
                merchantId, chainTxHash, payer, payee, occurredAt);
    }

    @Test
    void onPaymentCompleted_normalEvent_shouldFeedTransaction() {
        Instant ts = Instant.now();
        PaymentCompletedEvent ev = event(1L, new BigDecimal("100.50"), 200L,
                "0xabc", "0xpayer", "0xpayee", ts);

        collector.onPaymentCompleted(ev);

        List<OnChainTransaction> txs = dataSource.fetchAll();
        assertEquals(1, txs.size());
        OnChainTransaction tx = txs.get(0);
        assertEquals("0xabc", tx.getTxHash());
        assertEquals("0xpayer", tx.getFromAddress());
        assertEquals("0xpayee", tx.getToAddress());
        assertEquals(BigInteger.valueOf(100), tx.getAmount());
        assertEquals("200", tx.getMerchantId());
        assertEquals(ts, tx.getTimestamp());
        assertEquals(OnChainTransaction.Status.SUCCESS, tx.getStatus());
    }

    @Test
    void onPaymentCompleted_nullAmount_shouldUseZero() {
        PaymentCompletedEvent ev = event(1L, null, 200L, "0xabc", "0xpayer", "0xpayee", Instant.now());

        collector.onPaymentCompleted(ev);

        OnChainTransaction tx = dataSource.fetchAll().get(0);
        assertEquals(BigInteger.ZERO, tx.getAmount());
    }

    @Test
    void onPaymentCompleted_nullMerchantId_shouldKeepNull() {
        PaymentCompletedEvent ev = event(1L, new BigDecimal("10"), null, "0xabc", "0xpayer", "0xpayee", Instant.now());

        collector.onPaymentCompleted(ev);

        OnChainTransaction tx = dataSource.fetchAll().get(0);
        assertTrue(tx.getMerchantId() == null);
    }

    @Test
    void onPaymentCompleted_nullOccurredAt_shouldUseNow() {
        PaymentCompletedEvent ev = event(1L, new BigDecimal("10"), 1L, "0xabc", "0xpayer", "0xpayee", null);

        collector.onPaymentCompleted(ev);

        OnChainTransaction tx = dataSource.fetchAll().get(0);
        assertTrue(tx.getTimestamp() != null);
    }

    @Test
    void onPaymentCompleted_nullEvent_shouldBeNoOp() {
        collector.onPaymentCompleted(null);

        assertEquals(0, dataSource.fetchAll().size());
    }

    @Test
    void onPaymentCompleted_multipleEvents_shouldAccumulate() {
        collector.onPaymentCompleted(event(1L, new BigDecimal("10"), 1L, "h1", "A", "B", Instant.now()));
        collector.onPaymentCompleted(event(2L, new BigDecimal("20"), 2L, "h2", "C", "D", Instant.now()));

        assertEquals(2, dataSource.fetchAll().size());
    }

    @Test
    void onPaymentCompleted_decimalAmount_shouldTruncateToIntegral() {
        // BigDecimal.toBigInteger() 取整数部分
        PaymentCompletedEvent ev = event(1L, new BigDecimal("99.99"), 1L, "h", "A", "B", Instant.now());

        collector.onPaymentCompleted(ev);

        assertEquals(BigInteger.valueOf(99), dataSource.fetchAll().get(0).getAmount());
    }
}