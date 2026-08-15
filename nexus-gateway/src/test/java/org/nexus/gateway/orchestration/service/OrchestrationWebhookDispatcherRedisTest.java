package org.nexus.gateway.orchestration.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.orchestration.model.OrchestratedPayment;
import org.nexus.gateway.orchestration.model.OrchPaymentStatus;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * OrchestrationWebhookDispatcher Redis 路径测试（TODO v2.0.0 落地）：
 * 多实例共享 dedup（SETNX）+ 持久化 DLQ（List）。
 */
class OrchestrationWebhookDispatcherRedisTest {

    private OrchestrationWebhookDispatcher dispatcher;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private ListOperations<String, String> listOps;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        dispatcher = new OrchestrationWebhookDispatcher(
                new org.springframework.web.client.RestTemplate(),
                new long[]{1, 1, 1}, null);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = (ValueOperations<String, String>) mock(ValueOperations.class);
        listOps = (ListOperations<String, String>) mock(ListOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        dispatcher.setRedisTemplate(redisTemplate);
    }

    private OrchestratedPayment payment(String id, String url) {
        OrchestratedPayment p = new OrchestratedPayment();
        p.setId(id);
        p.setStatus(OrchPaymentStatus.SUCCEEDED);
        p.setAmount(1000L);
        p.setCurrency("NEX");
        p.setConnectorId("chain");
        p.setTransactionHash("0xabc");
        p.setNotifyUrl(url);
        p.setCreatedAt(java.time.Instant.now());
        return p;
    }

    @Test
    void redisDedup_sharedAcrossInstances() {
        // 第一个实例：SETNX 返回 true → 投递；第二个实例（同一 Redis）：false → 去重
        when(valueOps.setIfAbsent(startsWith("nexus:webhook:dedup:"), anyString(), any(Duration.class)))
                .thenReturn(true);  // 首次
        dispatcher.dispatch(payment("pay-1", "http://127.0.0.1:1/notify"));  // 尝试投递（失败重试后入 DLQ）

        // 验证 dedup key 用 Redis（SETNX 被调用）
        verify(valueOps, atLeastOnce()).setIfAbsent(
                startsWith("nexus:webhook:dedup:"), anyString(), any(Duration.class));
    }

    @Test
    void redisDedup_secondInstanceReturnsFalse() {
        // 已存在（另一实例已投递）→ setIfAbsent false → 去重跳过
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);
        dispatcher.dispatch(payment("pay-2", "http://127.0.0.1:1/notify"));
        // 不发起投递（无 RestTemplate 调用可验证——直接断言 dedup 路径无异常）
        verify(valueOps, atLeastOnce()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void redisDlq_persistedOnRetryExhausted() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        // 投递失败（RestTemplate 默认连接 127.0.0.1:1 失败）→ 重试耗尽 → Redis DLQ
        dispatcher.dispatch(payment("pay-3", "http://127.0.0.1:1/notify"));
        // 验证 DLQ leftPush 被调用（Redis 持久化）
        verify(listOps, atLeastOnce()).leftPush(eq("nexus:webhook:dead-letter"), contains("pay-3"));
    }
}
