package org.nexus.gateway.settlement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.clearing.SettlementPeriod;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link SettlementCycleService} 单元测试。
 *
 * <p>覆盖：resolveSettlementCycle、resolveSettlementPeriod、isEligibleForSettlement、
 * computeSettlementTime、createOrUpdateConfig 全部核心方法。</p>
 */
class SettlementCycleServiceTest {

    private MerchantSettlementConfigRepository configRepository;
    private SettlementCycleService service;

    @BeforeEach
    void setUp() {
        configRepository = mock(MerchantSettlementConfigRepository.class);
        service = new SettlementCycleService(configRepository);
    }

    // --- resolveSettlementCycle ---

    @Test
    void resolveSettlementCycle_noConfig_returnsDefaultT1() {
        when(configRepository.findByMerchantId(100L)).thenReturn(Optional.empty());

        assertEquals("T1", service.resolveSettlementCycle(100L));
    }

    @Test
    void resolveSettlementCycle_configT0_returnsT0() {
        MerchantSettlementConfig config = new MerchantSettlementConfig();
        config.setSettlementPeriod(SettlementPeriod.T0);
        when(configRepository.findByMerchantId(100L)).thenReturn(Optional.of(config));

        assertEquals("T0", service.resolveSettlementCycle(100L));
    }

    @Test
    void resolveSettlementCycle_configT2_returnsT2() {
        MerchantSettlementConfig config = new MerchantSettlementConfig();
        config.setSettlementPeriod(SettlementPeriod.T2);
        when(configRepository.findByMerchantId(100L)).thenReturn(Optional.of(config));

        assertEquals("T2", service.resolveSettlementCycle(100L));
    }

    @Test
    void resolveSettlementCycle_configT3_returnsT3() {
        MerchantSettlementConfig config = new MerchantSettlementConfig();
        config.setSettlementPeriod(SettlementPeriod.T3);
        when(configRepository.findByMerchantId(100L)).thenReturn(Optional.of(config));

        assertEquals("T3", service.resolveSettlementCycle(100L));
    }

    @Test
    void resolveSettlementCycle_configCustom5_returnsCustom5() {
        MerchantSettlementConfig config = new MerchantSettlementConfig();
        config.setSettlementPeriod(SettlementPeriod.CUSTOM);
        config.setCustomDays(5);
        when(configRepository.findByMerchantId(100L)).thenReturn(Optional.of(config));

        assertEquals("CUSTOM_5", service.resolveSettlementCycle(100L));
    }

    @Test
    void resolveSettlementCycle_configWeekly_returnsWeekly() {
        MerchantSettlementConfig config = new MerchantSettlementConfig();
        config.setSettlementPeriod(SettlementPeriod.WEEKLY);
        when(configRepository.findByMerchantId(100L)).thenReturn(Optional.of(config));

        assertEquals("WEEKLY", service.resolveSettlementCycle(100L));
    }

    @Test
    void resolveSettlementCycle_configMonthly_returnsMonthly() {
        MerchantSettlementConfig config = new MerchantSettlementConfig();
        config.setSettlementPeriod(SettlementPeriod.MONTHLY);
        when(configRepository.findByMerchantId(100L)).thenReturn(Optional.of(config));

        assertEquals("MONTHLY", service.resolveSettlementCycle(100L));
    }

    // --- resolveSettlementPeriod ---

    @Test
    void resolveSettlementPeriod_noConfig_returnsDefaultT1() {
        when(configRepository.findByMerchantId(100L)).thenReturn(Optional.empty());

        assertEquals(SettlementPeriod.T1, service.resolveSettlementPeriod(100L));
    }

    @Test
    void resolveSettlementPeriod_configT3_returnsT3() {
        MerchantSettlementConfig config = new MerchantSettlementConfig();
        config.setSettlementPeriod(SettlementPeriod.T3);
        when(configRepository.findByMerchantId(100L)).thenReturn(Optional.of(config));

        assertEquals(SettlementPeriod.T3, service.resolveSettlementPeriod(100L));
    }

    // --- isEligibleForSettlement ---

    @Test
    void isEligibleForSettlement_t0_alwaysTrue() {
        MerchantSettlementConfig config = new MerchantSettlementConfig();
        config.setSettlementPeriod(SettlementPeriod.T0);
        when(configRepository.findByMerchantId(100L)).thenReturn(Optional.of(config));

        LocalDateTime justNow = LocalDateTime.now();
        assertTrue(service.isEligibleForSettlement(justNow, 100L));
    }

    @Test
    void isEligibleForSettlement_t1_paidOneDayAgo_true() {
        MerchantSettlementConfig config = new MerchantSettlementConfig();
        config.setSettlementPeriod(SettlementPeriod.T1);
        when(configRepository.findByMerchantId(100L)).thenReturn(Optional.of(config));

        LocalDateTime paidAt = LocalDateTime.now().minusDays(1).minusMinutes(1);
        assertTrue(service.isEligibleForSettlement(paidAt, 100L));
    }

    @Test
    void isEligibleForSettlement_t1_paidJustNow_false() {
        MerchantSettlementConfig config = new MerchantSettlementConfig();
        config.setSettlementPeriod(SettlementPeriod.T1);
        when(configRepository.findByMerchantId(100L)).thenReturn(Optional.of(config));

        LocalDateTime paidAt = LocalDateTime.now();
        assertFalse(service.isEligibleForSettlement(paidAt, 100L));
    }

    @Test
    void isEligibleForSettlement_t2_paidOneDayAgo_false() {
        MerchantSettlementConfig config = new MerchantSettlementConfig();
        config.setSettlementPeriod(SettlementPeriod.T2);
        when(configRepository.findByMerchantId(100L)).thenReturn(Optional.of(config));

        LocalDateTime paidAt = LocalDateTime.now().minusDays(1).minusMinutes(1);
        assertFalse(service.isEligibleForSettlement(paidAt, 100L));
    }

    @Test
    void isEligibleForSettlement_t2_paidTwoDaysAgo_true() {
        MerchantSettlementConfig config = new MerchantSettlementConfig();
        config.setSettlementPeriod(SettlementPeriod.T2);
        when(configRepository.findByMerchantId(100L)).thenReturn(Optional.of(config));

        LocalDateTime paidAt = LocalDateTime.now().minusDays(2).minusMinutes(1);
        assertTrue(service.isEligibleForSettlement(paidAt, 100L));
    }

    @Test
    void isEligibleForSettlement_custom5_paidFourDaysAgo_false() {
        MerchantSettlementConfig config = new MerchantSettlementConfig();
        config.setSettlementPeriod(SettlementPeriod.CUSTOM);
        config.setCustomDays(5);
        when(configRepository.findByMerchantId(100L)).thenReturn(Optional.of(config));

        LocalDateTime paidAt = LocalDateTime.now().minusDays(4).minusMinutes(1);
        assertFalse(service.isEligibleForSettlement(paidAt, 100L));
    }

    @Test
    void isEligibleForSettlement_custom5_paidFiveDaysAgo_true() {
        MerchantSettlementConfig config = new MerchantSettlementConfig();
        config.setSettlementPeriod(SettlementPeriod.CUSTOM);
        config.setCustomDays(5);
        when(configRepository.findByMerchantId(100L)).thenReturn(Optional.of(config));

        LocalDateTime paidAt = LocalDateTime.now().minusDays(5).minusMinutes(1);
        assertTrue(service.isEligibleForSettlement(paidAt, 100L));
    }

    @Test
    void isEligibleForSettlement_noConfig_defaultsT1_paidOneDayAgo_true() {
        when(configRepository.findByMerchantId(100L)).thenReturn(Optional.empty());

        LocalDateTime paidAt = LocalDateTime.now().minusDays(1).minusMinutes(1);
        assertTrue(service.isEligibleForSettlement(paidAt, 100L));
    }

    // --- computeSettlementTime ---

    @Test
    void computeSettlementTime_t0_returnsPaidAtUnchanged() {
        LocalDateTime paidAt = LocalDateTime.now();
        assertEquals(paidAt, service.computeSettlementTime(paidAt, SettlementPeriod.T0, null));
    }

    @Test
    void computeSettlementTime_t1_returnsPaidAtPlusOneDay() {
        LocalDateTime paidAt = LocalDateTime.now();
        assertEquals(paidAt.plusDays(1), service.computeSettlementTime(paidAt, SettlementPeriod.T1, null));
    }

    @Test
    void computeSettlementTime_t2_returnsPaidAtPlusTwoDays() {
        LocalDateTime paidAt = LocalDateTime.now();
        assertEquals(paidAt.plusDays(2), service.computeSettlementTime(paidAt, SettlementPeriod.T2, null));
    }

    @Test
    void computeSettlementTime_t3_returnsPaidAtPlusThreeDays() {
        LocalDateTime paidAt = LocalDateTime.now();
        assertEquals(paidAt.plusDays(3), service.computeSettlementTime(paidAt, SettlementPeriod.T3, null));
    }

    @Test
    void computeSettlementTime_custom5_returnsPaidAtPlusFiveDays() {
        LocalDateTime paidAt = LocalDateTime.now();
        assertEquals(paidAt.plusDays(5), service.computeSettlementTime(paidAt, SettlementPeriod.CUSTOM, 5));
    }

    // --- createOrUpdateConfig ---

    @Test
    void createOrUpdateConfig_createNewConfig_success() {
        when(configRepository.findByMerchantId(100L)).thenReturn(Optional.empty());
        when(configRepository.save(any(MerchantSettlementConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        MerchantSettlementConfig result = service.createOrUpdateConfig(100L, SettlementPeriod.T2, null, true);

        assertNotNull(result);
        assertEquals(100L, result.getMerchantId());
        assertEquals(SettlementPeriod.T2, result.getSettlementPeriod());
        assertNull(result.getCustomDays());
        assertTrue(result.isAutoSettleEnabled());
        verify(configRepository).save(any(MerchantSettlementConfig.class));
    }

    @Test
    void createOrUpdateConfig_updateExistingConfig_success() {
        MerchantSettlementConfig existing = new MerchantSettlementConfig();
        existing.setMerchantId(100L);
        existing.setSettlementPeriod(SettlementPeriod.T1);
        when(configRepository.findByMerchantId(100L)).thenReturn(Optional.of(existing));
        when(configRepository.save(any(MerchantSettlementConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        MerchantSettlementConfig result = service.createOrUpdateConfig(100L, SettlementPeriod.T3, null, false);

        assertNotNull(result);
        assertEquals(SettlementPeriod.T3, result.getSettlementPeriod());
        assertNull(result.getCustomDays());
        assertFalse(result.isAutoSettleEnabled());
        verify(configRepository).save(existing);
    }

    @Test
    void createOrUpdateConfig_customDaysZero_throwsException() {
        assertThrows(IllegalArgumentException.class,
                () -> service.createOrUpdateConfig(100L, SettlementPeriod.CUSTOM, 0, true));
    }

    @Test
    void createOrUpdateConfig_customDays91_throwsException() {
        assertThrows(IllegalArgumentException.class,
                () -> service.createOrUpdateConfig(100L, SettlementPeriod.CUSTOM, 91, true));
    }

    @Test
    void createOrUpdateConfig_customDaysNull_throwsException() {
        assertThrows(IllegalArgumentException.class,
                () -> service.createOrUpdateConfig(100L, SettlementPeriod.CUSTOM, null, true));
    }
}