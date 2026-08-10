package org.nexus.bridge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link BridgeStatus} 单元测试：覆盖所有 getter/setter、剩余额度计算与 toString。
 */
class BridgeStatusTest {

    @Test
    @DisplayName("默认构造产生全零状态")
    void defaultConstructor_allZero() {
        BridgeStatus status = new BridgeStatus();
        assertNull(status.getState());
        assertEquals(0, status.getDailyUsed());
        assertEquals(0, status.getDailyLimit());
        assertEquals(0, status.getDailyTxCount());
        assertEquals(0, status.getPendingTxCount());
        assertEquals(0, status.getActiveValidatorCount());
        assertEquals(0, status.getSignatureThreshold());
    }

    @Test
    @DisplayName("所有 setter/getter 正确往返")
    void settersGetters_roundTrip() {
        BridgeStatus status = new BridgeStatus();
        status.setState(BridgeState.PAUSED);
        status.setDailyUsed(1000L);
        status.setDailyLimit(5000L);
        status.setDailyTxCount(10);
        status.setPendingTxCount(3);
        status.setActiveValidatorCount(5);
        status.setSignatureThreshold(3);

        assertEquals(BridgeState.PAUSED, status.getState());
        assertEquals(1000L, status.getDailyUsed());
        assertEquals(5000L, status.getDailyLimit());
        assertEquals(10, status.getDailyTxCount());
        assertEquals(3, status.getPendingTxCount());
        assertEquals(5, status.getActiveValidatorCount());
        assertEquals(3, status.getSignatureThreshold());
    }

    @Test
    @DisplayName("getDailyRemaining: 正常情况返回差额")
    void getDailyRemaining_normalCase() {
        BridgeStatus status = new BridgeStatus();
        status.setDailyLimit(1000L);
        status.setDailyUsed(300L);
        assertEquals(700L, status.getDailyRemaining());
    }

    @Test
    @DisplayName("getDailyRemaining: 已用超额时返回 0")
    void getDailyRemaining_clampedToZero() {
        BridgeStatus status = new BridgeStatus();
        status.setDailyLimit(100L);
        status.setDailyUsed(200L);
        assertEquals(0L, status.getDailyRemaining());
    }

    @Test
    @DisplayName("getDailyRemaining: 全部用完返回 0")
    void getDailyRemaining_allUsed() {
        BridgeStatus status = new BridgeStatus();
        status.setDailyLimit(100L);
        status.setDailyUsed(100L);
        assertEquals(0L, status.getDailyRemaining());
    }

    @Test
    @DisplayName("toString 应包含所有字段")
    void toString_containsAllFields() {
        BridgeStatus status = new BridgeStatus();
        status.setState(BridgeState.ACTIVE);
        status.setDailyLimit(1000L);
        status.setDailyUsed(300L);
        String str = status.toString();
        assertTrue(str.contains("ACTIVE"));
        assertTrue(str.contains("1000"));
        assertTrue(str.contains("300"));
        assertTrue(str.startsWith("BridgeStatus{"));
    }
}