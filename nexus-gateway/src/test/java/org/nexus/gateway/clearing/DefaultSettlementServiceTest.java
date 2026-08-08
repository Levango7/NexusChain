package org.nexus.gateway.clearing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.gateway.model.PaymentOrder;
import org.nexus.gateway.repository.PaymentOrderRepository;
import org.nexus.settlement.clearing.ClearingEngine;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link DefaultSettlementService} 单元测试：覆盖批次创建、执行成功/失败、
 * 状态查询、报表生成与参数校验分支。
 */
@ExtendWith(MockitoExtension.class)
class DefaultSettlementServiceTest {

    @Mock private SettlementBatchRepository batchRepository;
    @Mock private PaymentOrderRepository orderRepository;
    @Mock private ClearingEngine clearingEngine;

    private DefaultSettlementService service;

    @BeforeEach
    void setUp() {
        service = new DefaultSettlementService(batchRepository, orderRepository, clearingEngine);
    }

    // === createSettlementBatch ===

    @Test
    @DisplayName("createSettlementBatch: merchantId/period 为 null 抛异常")
    void create_nullArgs() {
        assertThrows(IllegalArgumentException.class,
                () -> service.createSettlementBatch(null, SettlementPeriod.T0));
        assertThrows(IllegalArgumentException.class,
                () -> service.createSettlementBatch(100L, null));
    }

    @Test
    @DisplayName("createSettlementBatch: 空订单列表 -> gross=0, fee=0, net=0")
    void create_emptyOrders() {
        when(orderRepository.findByMerchantIdAndStatusAndPaidAtBetween(
                eq(100L), any(), any(), any())).thenReturn(List.of());
        when(batchRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SettlementBatch batch = service.createSettlementBatch(100L, SettlementPeriod.T0);
        assertEquals(0, BigDecimal.ZERO.compareTo(batch.getTotalAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(batch.getFeeAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(batch.getNetAmount()));
        assertEquals(SettlementBatch.BatchStatus.OPEN, batch.getStatus());
        assertNotNull(batch.getBatchNo());
    }

    @Test
    @DisplayName("createSettlementBatch: 多订单聚合 gross/fee/net")
    void create_aggregateOrders() {
        PaymentOrder o1 = new PaymentOrder();
        o1.setId(1L);
        o1.setAmount(new BigDecimal("1000000"));
        PaymentOrder o2 = new PaymentOrder();
        o2.setId(2L);
        o2.setAmount(new BigDecimal("3000000"));
        when(orderRepository.findByMerchantIdAndStatusAndPaidAtBetween(
                eq(100L), any(), any(), any())).thenReturn(List.of(o1, o2));
        when(batchRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SettlementBatch batch = service.createSettlementBatch(100L, SettlementPeriod.T1);
        // gross = 4_000_000, fee = 4_000_000 * 50 / 10000 = 20000, net = 3980000
        assertEquals(0, new BigDecimal("4000000").compareTo(batch.getTotalAmount()));
        assertEquals(0, new BigDecimal("20000").compareTo(batch.getFeeAmount()));
        assertEquals(0, new BigDecimal("3980000").compareTo(batch.getNetAmount()));
        assertEquals("1,2", batch.getTransactionIdsCsv());
    }

    // === executeSettlement ===

    @Test
    @DisplayName("executeSettlement: 批次不存在抛异常")
    void execute_notFound() {
        when(batchRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.executeSettlement(99L));
    }

    @Test
    @DisplayName("executeSettlement: 非 OPEN 状态抛异常")
    void execute_notOpen() {
        SettlementBatch batch = newBatch(1L, SettlementBatch.BatchStatus.COMPLETED);
        when(batchRepository.findById(1L)).thenReturn(Optional.of(batch));
        assertThrows(IllegalStateException.class, () -> service.executeSettlement(1L));
    }

    @Test
    @DisplayName("executeSettlement: ClearingEngine SETTLED -> COMPLETED")
    void execute_settled() {
        SettlementBatch batch = newBatch(1L, SettlementBatch.BatchStatus.OPEN);
        batch.setBatchNo("SB-1");
        batch.setNetAmount(new BigDecimal("1000"));
        batch.setPeriod(SettlementPeriod.T0);
        when(batchRepository.findById(1L)).thenReturn(Optional.of(batch));
        when(batchRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        org.nexus.settlement.clearing.SettlementBatch engineBatch =
                new org.nexus.settlement.clearing.SettlementBatch();
        engineBatch.setStatus(org.nexus.settlement.clearing.SettlementBatch.BatchStatus.SETTLED);
        when(clearingEngine.batchClear(any())).thenReturn(engineBatch);

        SettlementBatch result = service.executeSettlement(1L);
        assertEquals(SettlementBatch.BatchStatus.COMPLETED, result.getStatus());
        assertNotNull(result.getExecutedAt());
        assertNull(result.getChainTxHash());
    }

    @Test
    @DisplayName("executeSettlement: ClearingEngine 非 SETTLED -> FAILED")
    void execute_notSettled() {
        SettlementBatch batch = newBatch(1L, SettlementBatch.BatchStatus.OPEN);
        batch.setBatchNo("SB-2");
        batch.setNetAmount(new BigDecimal("1000"));
        batch.setPeriod(SettlementPeriod.T0);
        when(batchRepository.findById(1L)).thenReturn(Optional.of(batch));
        when(batchRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        org.nexus.settlement.clearing.SettlementBatch engineBatch =
                new org.nexus.settlement.clearing.SettlementBatch();
        engineBatch.setStatus(org.nexus.settlement.clearing.SettlementBatch.BatchStatus.PENDING);
        when(clearingEngine.batchClear(any())).thenReturn(engineBatch);

        SettlementBatch result = service.executeSettlement(1L);
        assertEquals(SettlementBatch.BatchStatus.FAILED, result.getStatus());
    }

    @Test
    @DisplayName("executeSettlement: ClearingEngine 返回 null -> FAILED")
    void execute_nullResult() {
        SettlementBatch batch = newBatch(1L, SettlementBatch.BatchStatus.OPEN);
        batch.setBatchNo("SB-3");
        batch.setNetAmount(new BigDecimal("1000"));
        batch.setPeriod(SettlementPeriod.T0);
        when(batchRepository.findById(1L)).thenReturn(Optional.of(batch));
        when(batchRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(clearingEngine.batchClear(any())).thenReturn(null);

        SettlementBatch result = service.executeSettlement(1L);
        assertEquals(SettlementBatch.BatchStatus.FAILED, result.getStatus());
    }

    @Test
    @DisplayName("executeSettlement: ClearingEngine 抛异常 -> FAILED")
    void execute_exception() {
        SettlementBatch batch = newBatch(1L, SettlementBatch.BatchStatus.OPEN);
        batch.setBatchNo("SB-4");
        batch.setNetAmount(new BigDecimal("1000"));
        batch.setPeriod(SettlementPeriod.T0);
        when(batchRepository.findById(1L)).thenReturn(Optional.of(batch));
        when(batchRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(clearingEngine.batchClear(any())).thenThrow(new RuntimeException("engine down"));

        SettlementBatch result = service.executeSettlement(1L);
        assertEquals(SettlementBatch.BatchStatus.FAILED, result.getStatus());
    }

    // === getSettlementStatus ===

    @Test
    @DisplayName("getSettlementStatus: null 返回 null")
    void getStatus_null() {
        assertNull(service.getSettlementStatus(null));
    }

    @Test
    @DisplayName("getSettlementStatus: 存在返回批次，不存在返回 null")
    void getStatus_presentAndMissing() {
        SettlementBatch batch = newBatch(1L, SettlementBatch.BatchStatus.OPEN);
        when(batchRepository.findById(1L)).thenReturn(Optional.of(batch));
        assertNotNull(service.getSettlementStatus(1L));

        when(batchRepository.findById(2L)).thenReturn(Optional.empty());
        assertNull(service.getSettlementStatus(2L));
    }

    // === generateSettlementReport ===

    @Test
    @DisplayName("generateSettlementReport: merchantId 为 null 返回空列表")
    void report_nullMerchant() {
        assertTrue(service.generateSettlementReport(null, SettlementPeriod.T0).isEmpty());
    }

    @Test
    @DisplayName("generateSettlementReport: 带 period 走 findByMerchantIdAndPeriod")
    void report_withPeriod() {
        when(batchRepository.findByMerchantIdAndPeriod(100L, SettlementPeriod.T0))
                .thenReturn(List.of(newBatch(1L, SettlementBatch.BatchStatus.COMPLETED)));
        assertEquals(1, service.generateSettlementReport(100L, SettlementPeriod.T0).size());
    }

    @Test
    @DisplayName("generateSettlementReport: period 为 null 走 findByMerchantId")
    void report_withoutPeriod() {
        when(batchRepository.findByMerchantId(100L))
                .thenReturn(List.of(newBatch(1L, SettlementBatch.BatchStatus.COMPLETED)));
        assertEquals(1, service.generateSettlementReport(100L, null).size());
    }

    private SettlementBatch newBatch(Long id, SettlementBatch.BatchStatus status) {
        SettlementBatch b = new SettlementBatch();
        b.setId(id);
        b.setStatus(status);
        b.setMerchantId(100L);
        b.setPeriod(SettlementPeriod.T0);
        b.setWindowStart(LocalDateTime.now().minusDays(1));
        b.setWindowEnd(LocalDateTime.now());
        return b;
    }
}
