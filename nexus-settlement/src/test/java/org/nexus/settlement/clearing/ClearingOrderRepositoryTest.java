package org.nexus.settlement.clearing;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ClearingOrderRepository} 集成测试（真实 H2 落库）。
 *
 * <p>覆盖：PENDING 落库、findByStatus 取批、同键 upsert 回写 SETTLED+settlementTxHash
 * （settle 终态回填路径）、全字段持久化往返（JPQL 投影强制走 DB）。</p>
 */
@DataJpaTest
class ClearingOrderRepositoryTest {

    @Autowired
    private ClearingOrderRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    private ClearingOrder order(String orderId, ClearingOrder.OrderStatus status) {
        ClearingOrder o = new ClearingOrder();
        o.setOrderId(orderId);
        o.setMerchantId("M001");
        o.setAmount(new BigDecimal("100.50"));
        o.setCurrency("USD");
        o.setSettlementCycle("T0");
        o.setStatus(status);
        o.setCreatedAt(Instant.now());
        o.setPaymentId(1L);
        o.setChainTxHash("0xabc");
        o.setConnectorId("POLYGON");
        o.setRoutingLatencyMs(42L);
        o.setCostBps(5);
        o.setPayerAddress("0xpayer");
        o.setPayeeAddress("0xpayee");
        return o;
    }

    @Test
    void save_pending_shouldPersistAndFindByStatus() {
        repository.save(order("O1", ClearingOrder.OrderStatus.PENDING));
        repository.save(order("O2", ClearingOrder.OrderStatus.PENDING));
        repository.save(order("O3", ClearingOrder.OrderStatus.SETTLED));

        List<ClearingOrder> pending = repository.findByStatus(ClearingOrder.OrderStatus.PENDING);

        assertEquals(2, pending.size());
        assertTrue(pending.stream().allMatch(o -> o.getStatus() == ClearingOrder.OrderStatus.PENDING));
    }

    @Test
    void saveSameKey_shouldUpsertTerminalState() {
        ClearingOrder o = order("O1", ClearingOrder.OrderStatus.PENDING);
        repository.save(o);

        // settle 终态回填：同键回写 SETTLED + settlementTxHash
        o.setStatus(ClearingOrder.OrderStatus.SETTLED);
        o.setSettlementTxHash("0xsettled");
        repository.save(o);

        Optional<ClearingOrder> reloaded = repository.findById("O1");
        assertTrue(reloaded.isPresent());
        assertEquals(ClearingOrder.OrderStatus.SETTLED, reloaded.get().getStatus());
        assertEquals("0xsettled", reloaded.get().getSettlementTxHash());
    }

    @Test
    void save_allFields_shouldRoundTrip() {
        ClearingOrder original = order("O9", ClearingOrder.OrderStatus.PENDING);
        repository.saveAndFlush(original);

        // @DataJpaTest 事务内 findById 会命中一级缓存直接返回同一实例，
        // 用 JPQL 投影查询单列强制走 DB，逐字段验证落库往返。
        Object[] row = entityManager.getEntityManager()
                .createQuery(
                        "select o.merchantId, o.amount, o.currency, o.settlementCycle, "
                                + "o.paymentId, o.chainTxHash, o.connectorId, o.routingLatencyMs, "
                                + "o.costBps, o.payerAddress, o.payeeAddress "
                                + "from ClearingOrder o where o.orderId = 'O9'",
                        Object[].class)
                .getSingleResult();

        assertEquals("M001", row[0]);
        assertEquals(0, new BigDecimal("100.50").compareTo((BigDecimal) row[1]));
        assertEquals("USD", row[2]);
        assertEquals("T0", row[3]);
        assertEquals(1L, row[4]);
        assertEquals("0xabc", row[5]);
        assertEquals("POLYGON", row[6]);
        assertEquals(42L, row[7]);
        assertEquals(5, row[8]);
        assertEquals("0xpayer", row[9]);
        assertEquals("0xpayee", row[10]);
    }
}