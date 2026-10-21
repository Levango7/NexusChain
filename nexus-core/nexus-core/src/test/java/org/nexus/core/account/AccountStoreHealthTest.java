package org.nexus.core.account;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 降级状态必须能被运维接口观察到，且恢复后自动回到 UP。
 */
public class AccountStoreHealthTest {

    @Test
    void failureMarksDegradedAndSuccessRecovers() {
        AccountStoreHealth health = new AccountStoreHealth();

        health.recordFailure(new IllegalStateException("database down"));
        assertTrue(health.isDegraded());
        Map<String, Object> degraded = health.snapshot();
        assertEquals("DEGRADED", degraded.get("state"));
        assertTrue(String.valueOf(degraded.get("lastFailure")).contains("database down"));

        health.recordSuccess();
        assertFalse(health.isDegraded());
        assertEquals("UP", health.snapshot().get("state"));
    }
}
