package org.nexus.bridge.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 桥 messaging 观测端点测试（PLAN-037 切片 3）。
 *
 * <p>锁定：装配关闭（Bean 不存在）⇒ 显式 {@code relayEnabled=false + reason}；
 * 装配开启 ⇒ 完整快照字段。</p>
 */
class BridgeMessagingControllerTest {

    @Test
    @SuppressWarnings("unchecked")
    void disabledReturnsExplicitRelayEnabledFalse() {
        // getIfAvailable() 默认返回 null（无 Bean）
        ObjectProvider<BridgeMessagingStatus> provider = mock(ObjectProvider.class);

        ResponseEntity<Map<String, Object>> resp =
                new BridgeMessagingController(provider).messaging();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(false, resp.getBody().get("relayEnabled"));
        assertNotNull(resp.getBody().get("reason"), "需给出未装配的可行动原因");
    }

    @Test
    @SuppressWarnings("unchecked")
    void enabledReturnsFullSnapshot() {
        BridgeMessagingStatus status =
                new BridgeMessagingStatus(true, 2, 3600L, "InMemoryMessageStore", 1);
        ObjectProvider<BridgeMessagingStatus> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(status);

        ResponseEntity<Map<String, Object>> resp =
                new BridgeMessagingController(provider).messaging();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<String, Object> body = resp.getBody();
        assertNotNull(body);
        assertEquals(true, body.get("relayEnabled"));
        assertEquals(2, body.get("requiredSignatures"));
        assertEquals(3600L, body.get("messageTimeout"));
        assertEquals("InMemoryMessageStore", body.get("storeType"));
        assertEquals(1, body.get("targetAdapters"));
    }
}
