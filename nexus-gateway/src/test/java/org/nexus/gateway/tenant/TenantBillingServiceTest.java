package org.nexus.gateway.tenant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link TenantBillingService} 单元测试（P4-T6 多租户改造）。
 *
 * <p>覆盖按租户费率计算手续费、使用量累加、默认费率降级。</p>
 */
@ExtendWith(MockitoExtension.class)
class TenantBillingServiceTest {

    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private TenantUsageRecordRepository usageRecordRepository;

    private TenantBillingService billingService;

    @BeforeEach
    void setUp() {
        // 默认费率 100 bps = 1%
        billingService = new TenantBillingService(tenantRepository, usageRecordRepository, 100);
    }

    private Tenant tenantWithFeeRate(String tenantId, int feeRateBps) {
        Tenant t = new Tenant();
        t.setTenantId(tenantId);
        t.setConfig(new TenantConfig());
        t.getConfig().setFeeRateBps(feeRateBps);
        return t;
    }

    @Test
    @DisplayName("calculateFee: 按租户费率计算（100 bps = 1%）")
    void calculateFeeByTenantRate() {
        Tenant t = tenantWithFeeRate("t-1", 100); // 1%
        when(tenantRepository.findByTenantId("t-1")).thenReturn(Optional.of(t));

        // 1000000 * 1% = 10000
        BigDecimal fee = billingService.calculateFee("t-1", new BigDecimal("1000000"));
        assertEquals(0, new BigDecimal("10000").compareTo(fee));
    }

    @Test
    @DisplayName("calculateFee: 不同租户不同费率")
    void differentTenantsDifferentRates() {
        Tenant tA = tenantWithFeeRate("t-a", 100); // 1%
        Tenant tB = tenantWithFeeRate("t-b", 50);  // 0.5%
        when(tenantRepository.findByTenantId("t-a")).thenReturn(Optional.of(tA));
        when(tenantRepository.findByTenantId("t-b")).thenReturn(Optional.of(tB));

        BigDecimal amount = new BigDecimal("1000000");
        BigDecimal feeA = billingService.calculateFee("t-a", amount);
        BigDecimal feeB = billingService.calculateFee("t-b", amount);

        assertEquals(0, new BigDecimal("10000").compareTo(feeA));  // 1% of 1000000
        assertEquals(0, new BigDecimal("5000").compareTo(feeB));   // 0.5% of 1000000
    }

    @Test
    @DisplayName("calculateFee: 租户未配置时使用默认费率")
    void defaultFeeRateWhenTenantNotFound() {
        when(tenantRepository.findByTenantId("ghost")).thenReturn(Optional.empty());

        // 默认 100 bps = 1%
        BigDecimal fee = billingService.calculateFee("ghost", new BigDecimal("1000000"));
        assertEquals(0, new BigDecimal("10000").compareTo(fee));
    }

    @Test
    @DisplayName("calculateFee: null/负金额返回 0")
    void nullOrNegativeAmountReturnsZero() {
        assertEquals(0, BigDecimal.ZERO.compareTo(billingService.calculateFee("t-1", null)));
        assertEquals(0, BigDecimal.ZERO.compareTo(billingService.calculateFee("t-1", BigDecimal.ZERO)));
        assertEquals(0, BigDecimal.ZERO.compareTo(billingService.calculateFee("t-1", new BigDecimal("-100"))));
    }

    @Test
    @DisplayName("calculateFee: 向下取整（对平台有利的最小化误差）")
    void feeRoundedDown() {
        // 100 bps, amount = 10005 → fee = 10005 * 100 / 10000 = 100.05 → 向下取整 = 100
        Tenant t = tenantWithFeeRate("t-1", 100);
        when(tenantRepository.findByTenantId("t-1")).thenReturn(Optional.of(t));

        BigDecimal fee = billingService.calculateFee("t-1", new BigDecimal("10005"));
        assertEquals(0, new BigDecimal("100").compareTo(fee));
    }

    @Test
    @DisplayName("calculateFee(bps, amount): 直接传入费率")
    void calculateFeeWithExplicitRate() {
        // 50 bps = 0.5%, amount = 1000000 → fee = 5000
        BigDecimal fee = billingService.calculateFee(50, new BigDecimal("1000000"));
        assertEquals(0, new BigDecimal("5000").compareTo(fee));
    }

    @Test
    @DisplayName("recordUsage: 首次记录创建新记录")
    void recordUsageCreatesNewRecord() {
        when(usageRecordRepository.findByTenantIdAndPeriod(eq("t-1"), anyString()))
                .thenReturn(Optional.empty());
        when(usageRecordRepository.save(any(TenantUsageRecord.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        LocalDateTime now = LocalDateTime.of(2026, 8, 9, 12, 0);
        TenantUsageRecord result = billingService.recordUsage(
                "t-1", new BigDecimal("1000000"), new BigDecimal("10000"), now);

        assertEquals("t-1", result.getTenantId());
        assertEquals("2026-08", result.getPeriod());
        assertEquals(1L, result.getTransactionCount());
        assertEquals(0, new BigDecimal("1000000").compareTo(result.getTotalAmount()));
        assertEquals(0, new BigDecimal("10000").compareTo(result.getTotalFee()));
    }

    @Test
    @DisplayName("recordUsage: 多次记录累加")
    void recordUsageAccumulates() {
        TenantUsageRecord existing = new TenantUsageRecord();
        existing.setTenantId("t-1");
        existing.setPeriod("2026-08");
        existing.setTransactionCount(2L);
        existing.setTotalAmount(new BigDecimal("2000000"));
        existing.setTotalFee(new BigDecimal("20000"));

        when(usageRecordRepository.findByTenantIdAndPeriod(eq("t-1"), anyString()))
                .thenReturn(Optional.of(existing));
        when(usageRecordRepository.save(any(TenantUsageRecord.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        LocalDateTime now = LocalDateTime.of(2026, 8, 9, 12, 0);
        TenantUsageRecord result = billingService.recordUsage(
                "t-1", new BigDecimal("500000"), new BigDecimal("5000"), now);

        assertEquals(3L, result.getTransactionCount());
        assertEquals(0, new BigDecimal("2500000").compareTo(result.getTotalAmount()));
        assertEquals(0, new BigDecimal("25000").compareTo(result.getTotalFee()));
    }

    @Test
    @DisplayName("recordUsage: null amount/fee 按 0 处理")
    void recordUsageNullAmountFeeTreatedAsZero() {
        when(usageRecordRepository.findByTenantIdAndPeriod(eq("t-1"), anyString()))
                .thenReturn(Optional.empty());
        when(usageRecordRepository.save(any(TenantUsageRecord.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        TenantUsageRecord result = billingService.recordUsage(
                "t-1", null, null, LocalDateTime.now());

        assertEquals(1L, result.getTransactionCount());
        assertEquals(0, BigDecimal.ZERO.compareTo(result.getTotalAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(result.getTotalFee()));
    }

    @Test
    @DisplayName("getUsage: 委托 repository 查询")
    void getUsageDelegatesToRepository() {
        TenantUsageRecord record = new TenantUsageRecord();
        record.setTenantId("t-1");
        record.setPeriod("2026-08");
        when(usageRecordRepository.findByTenantIdAndPeriod("t-1", "2026-08"))
                .thenReturn(Optional.of(record));

        Optional<TenantUsageRecord> result = billingService.getUsage("t-1", "2026-08");

        assertTrue(result.isPresent());
        assertEquals("t-1", result.get().getTenantId());
    }
}