package org.nexus.bridge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.bridge.repository.BridgeTransactionRepository;
import org.nexus.bridge.safety.DailyUsageStore;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PLAN-038 接线测试：{@link BridgeServiceImpl} 与 {@link DailyUsageStore}
 * 的委托关系（状态读取 / 预留 / 拒绝映射 / 归还）。
 *
 * <p>store 本体语义（窗口滚动、限额谓词、重启/多副本）在其自身测试类覆盖；
 * 本类只锁定接线。</p>
 */
@ExtendWith(MockitoExtension.class)
class BridgeDailyUsageWiringTest {

    @Mock
    private BridgeTransactionRepository txRepository;

    @Mock
    private DailyUsageStore dailyUsageStore;

    private BridgeConfig config;
    private BridgeServiceImpl service;

    @BeforeEach
    void setUp() {
        config = new BridgeConfig();
        config.setDailyLimit(100L);
        service = new BridgeServiceImpl(config, txRepository);
        ReflectionTestUtils.setField(service, "dailyUsageStore", dailyUsageStore);
    }

    @Test
    void getStatusReadsUsageFromStore() {
        when(dailyUsageStore.currentUsed(anyLong())).thenReturn(42L);
        assertEquals(42L, service.getStatus().getDailyUsed(), "getStatus 应读 store（而非内部计数器）");
    }

    @Test
    void reserveDelegatesToStoreAndRejectionMapsToBridgeException() {
        when(dailyUsageStore.tryReserve(eq(30L), eq(100L), anyLong())).thenReturn(false);
        BridgeException e = assertThrows(BridgeException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "reserveDailyQuota", 30L));
        assertEquals("Daily limit exceeded", e.getMessage(), "拒绝应映射为历史同款 BridgeException");

        when(dailyUsageStore.tryReserve(eq(30L), eq(100L), anyLong())).thenReturn(true);
        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(service, "reserveDailyQuota", 30L));
    }

    @Test
    void releaseDelegatesToStore() {
        ReflectionTestUtils.invokeMethod(service, "releaseDailyQuota", 7L);
        verify(dailyUsageStore).release(eq(7L), anyLong());
    }
}
