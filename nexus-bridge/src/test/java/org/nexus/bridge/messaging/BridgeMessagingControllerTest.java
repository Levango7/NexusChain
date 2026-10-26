package org.nexus.bridge.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 桥 messaging 观测端点测试（PLAN-037 切片 3；PLAN-046 追加事件源字段）。
 *
 * <p>锁定：装配关闭（Bean 不存在）⇒ 显式 {@code relayEnabled=false + reason}；
 * 装配开启 ⇒ 完整快照字段；事件源开关开启 ⇒ {@code eventSource} 子对象带活值
 * （计数来自 worker 实时快照，不是启动时冻结值）。</p>
 */
class BridgeMessagingControllerTest {

    @Test
    @SuppressWarnings("unchecked")
    void disabledReturnsExplicitRelayEnabledFalse() {
        // getIfAvailable() 默认返回 null（无 Bean）
        ObjectProvider<BridgeMessagingStatus> provider = mock(ObjectProvider.class);

        ResponseEntity<Map<String, Object>> resp =
                new BridgeMessagingController(provider, eventSourceProvider(null)).messaging();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(false, resp.getBody().get("relayEnabled"));
        assertNotNull(resp.getBody().get("reason"), "需给出未装配的可行动原因");
        assertFalse(resp.getBody().containsKey("eventSource"),
                "事件源未装配时不得带该字段（不用 0 冒充）");
    }

    @Test
    @SuppressWarnings("unchecked")
    void enabledReturnsFullSnapshot() {
        BridgeMessagingStatus status =
                new BridgeMessagingStatus(true, 2, 3600L, "InMemoryMessageStore", 1);
        ObjectProvider<BridgeMessagingStatus> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(status);

        ResponseEntity<Map<String, Object>> resp =
                new BridgeMessagingController(provider, eventSourceProvider(null)).messaging();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<String, Object> body = resp.getBody();
        assertNotNull(body);
        assertEquals(true, body.get("relayEnabled"));
        assertEquals(2, body.get("requiredSignatures"));
        assertEquals(3600L, body.get("messageTimeout"));
        assertEquals("InMemoryMessageStore", body.get("storeType"));
        assertEquals(1, body.get("targetAdapters"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void eventSourceSnapshotIsLiveNotFrozenAtZero() throws Exception {
        BridgeMessagingStatus status =
                new BridgeMessagingStatus(true, 1, 3600L, "InMemoryMessageStore", 1);
        ObjectProvider<BridgeMessagingStatus> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(status);

        // 真 worker + 模拟源：先跑一轮让计数非 0，再取端点快照
        SimulatedChainEventSource source = new SimulatedChainEventSource(
                "L1-sim", "L2-sim", "0xsrc", "0xdst", 0L);
        MessageRelayer relayer = new MessageRelayer(new MessageFormatter(),
                new InMemoryMessageStore(), bridgeConfig());
        BridgeEventSourceWorker worker = new BridgeEventSourceWorker(
                source, new MessageFormatter(), relayer,
                MessageRelayer.privateKeyToHex(MessageRelayer.generateKeyPair()), 1);
        assertEquals(1, worker.runOnce(), "模拟源一轮应中继 1 条");

        ResponseEntity<Map<String, Object>> resp =
                new BridgeMessagingController(provider, eventSourceProvider(worker)).messaging();

        Map<String, Object> body = resp.getBody();
        assertNotNull(body);
        Map<?, ?> event = (Map<?, ?>) body.get("eventSource");
        assertNotNull(event, "事件源开关开启时必须带 eventSource");
        assertEquals(true, event.get("sourceEnabled"));
        assertEquals("L1-sim", event.get("sourceChain"));
        assertEquals("L2-sim", event.get("targetChain"));
        assertEquals("SimulatedChainEventSource", event.get("sourceType"));
        assertEquals(1L, event.get("pollCount"));
        assertEquals(1L, event.get("relayedCount"));
        assertEquals(0L, event.get("failedCount"));
        assertEquals(0L, event.get("lastNonce"));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<BridgeEventSourceWorker> eventSourceProvider(BridgeEventSourceWorker worker) {
        ObjectProvider<BridgeEventSourceWorker> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(worker);
        return provider;
    }

    private static MessageConfig bridgeConfig() {
        MessageConfig config = new MessageConfig();
        config.setRequiredSignatures(1);
        return config;
    }
}
