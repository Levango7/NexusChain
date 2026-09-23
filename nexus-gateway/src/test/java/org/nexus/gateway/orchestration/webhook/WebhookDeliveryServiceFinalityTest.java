package org.nexus.gateway.orchestration.webhook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.model.FinalityStatus;
import org.nexus.gateway.webhook.WebhookUrlValidator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * WebhookDeliveryService 最终性阈值检查测试 — 验证 shouldSuppressByFinality / meetsThreshold 逻辑。
 *
 * <p>测试策略：通过 deliver() 方法间接测试 private 的最终性检查逻辑。
 * 当 finalityStatus 低于阈值时，deliver() 在 urlValidator.validate() 之后、
 * repository.findByPaymentIdAndStatus() 之前返回 null（被抑制）。
 * 当 finalityStatus 达到阈值时，deliver() 继续执行到 repository.findByPaymentIdAndStatus()。</p>
 */
class WebhookDeliveryServiceFinalityTest {

    private WebhookDeliveryRepository repository;
    private WebhookRetryService retryService;
    private WebhookSignatureService signatureService;
    private DeadLetterSender deadLetterSender;
    private RestTemplate restTemplate;
    private WebhookUrlValidator urlValidator;

    @BeforeEach
    void setUp() {
        repository = mock(WebhookDeliveryRepository.class);
        retryService = mock(WebhookRetryService.class);
        signatureService = mock(WebhookSignatureService.class);
        deadLetterSender = mock(DeadLetterSender.class);
        restTemplate = mock(RestTemplate.class);
        urlValidator = mock(WebhookUrlValidator.class);
    }

    /** 使用 @Autowired 构造器创建 service，可指定 finalityThreshold。 */
    private WebhookDeliveryService createService(String finalityThreshold) {
        return new WebhookDeliveryService(
                repository, retryService, signatureService, deadLetterSender,
                restTemplate, new com.fasterxml.jackson.databind.ObjectMapper(),
                "test-secret", urlValidator, finalityThreshold);
    }

    private Map<String, Object> payloadWithFinality(String finalityStatus) {
        return Map.of(
                "event", "payment.succeeded",
                "orderId", 1L,
                "finalityStatus", finalityStatus
        );
    }

    // ==================== 被抑制场景（finalityStatus 低于阈值） ====================

    @Test
    void unknownBelowOptimisticThresholdIsSuppressed() {
        WebhookDeliveryService service = createService("OPTIMISTIC");

        WebhookDeliveryRecord result = service.deliver(
                "pay-001", 1L, "https://example.com/webhook",
                payloadWithFinality("UNKNOWN"), "payment.succeeded");

        assertNull(result, "UNKNOWN 低于 OPTIMISTIC 阈值，应被抑制");
        verify(urlValidator).validate("https://example.com/webhook");
        verify(repository, never()).findByPaymentIdAndStatus(any(), any());
    }

    @Test
    void optimisticBelowFinalizedThresholdIsSuppressed() {
        WebhookDeliveryService service = createService("FINALIZED");

        WebhookDeliveryRecord result = service.deliver(
                "pay-001", 1L, "https://example.com/webhook",
                payloadWithFinality("OPTIMISTIC"), "payment.succeeded");

        assertNull(result, "OPTIMISTIC 低于 FINALIZED 阈值，应被抑制");
        verify(repository, never()).findByPaymentIdAndStatus(any(), any());
    }

    @Test
    void finalizingBelowFinalizedThresholdIsSuppressed() {
        WebhookDeliveryService service = createService("FINALIZED");

        WebhookDeliveryRecord result = service.deliver(
                "pay-001", 1L, "https://example.com/webhook",
                payloadWithFinality("FINALIZING"), "payment.succeeded");

        assertNull(result, "FINALIZING 低于 FINALIZED 阈值，应被抑制");
        verify(repository, never()).findByPaymentIdAndStatus(any(), any());
    }

    @Test
    void unknownBelowFinalizingThresholdIsSuppressed() {
        WebhookDeliveryService service = createService("FINALIZING");

        WebhookDeliveryRecord result = service.deliver(
                "pay-001", 1L, "https://example.com/webhook",
                payloadWithFinality("UNKNOWN"), "payment.succeeded");

        assertNull(result, "UNKNOWN 低于 FINALIZING 阈值，应被抑制");
        verify(repository, never()).findByPaymentIdAndStatus(any(), any());
    }

    // ==================== 不被抑制场景（finalityStatus 达到阈值） ====================

    @Test
    void optimisticMeetsOptimisticThresholdNotSuppressed() {
        WebhookDeliveryService service = createService("OPTIMISTIC");
        WebhookDeliveryRecord existingRecord = new WebhookDeliveryRecord();
        existingRecord.setDeliveryId("existing");
        when(repository.findByPaymentIdAndStatus("pay-001", "payment.succeeded"))
                .thenReturn(Optional.of(existingRecord));

        WebhookDeliveryRecord result = service.deliver(
                "pay-001", 1L, "https://example.com/webhook",
                payloadWithFinality("OPTIMISTIC"), "payment.succeeded");

        assertNotNull(result, "OPTIMISTIC 达到 OPTIMISTIC 阈值，不应被抑制");
        assertEquals("existing", result.getDeliveryId());
        verify(repository).findByPaymentIdAndStatus("pay-001", "payment.succeeded");
    }

    @Test
    void finalizingMeetsOptimisticThresholdNotSuppressed() {
        WebhookDeliveryService service = createService("OPTIMISTIC");
        WebhookDeliveryRecord existingRecord = new WebhookDeliveryRecord();
        existingRecord.setDeliveryId("existing");
        when(repository.findByPaymentIdAndStatus("pay-001", "payment.succeeded"))
                .thenReturn(Optional.of(existingRecord));

        WebhookDeliveryRecord result = service.deliver(
                "pay-001", 1L, "https://example.com/webhook",
                payloadWithFinality("FINALIZING"), "payment.succeeded");

        assertNotNull(result, "FINALIZING 达到 OPTIMISTIC 阈值，不应被抑制");
        verify(repository).findByPaymentIdAndStatus("pay-001", "payment.succeeded");
    }

    @Test
    void finalizedMeetsOptimisticThresholdNotSuppressed() {
        WebhookDeliveryService service = createService("OPTIMISTIC");
        WebhookDeliveryRecord existingRecord = new WebhookDeliveryRecord();
        existingRecord.setDeliveryId("existing");
        when(repository.findByPaymentIdAndStatus("pay-001", "payment.succeeded"))
                .thenReturn(Optional.of(existingRecord));

        WebhookDeliveryRecord result = service.deliver(
                "pay-001", 1L, "https://example.com/webhook",
                payloadWithFinality("FINALIZED"), "payment.succeeded");

        assertNotNull(result, "FINALIZED 达到 OPTIMISTIC 阈值，不应被抑制");
        verify(repository).findByPaymentIdAndStatus("pay-001", "payment.succeeded");
    }

    @Test
    void finalizedMeetsFinalizedThresholdNotSuppressed() {
        WebhookDeliveryService service = createService("FINALIZED");
        WebhookDeliveryRecord existingRecord = new WebhookDeliveryRecord();
        existingRecord.setDeliveryId("existing");
        when(repository.findByPaymentIdAndStatus("pay-001", "payment.succeeded"))
                .thenReturn(Optional.of(existingRecord));

        WebhookDeliveryRecord result = service.deliver(
                "pay-001", 1L, "https://example.com/webhook",
                payloadWithFinality("FINALIZED"), "payment.succeeded");

        assertNotNull(result, "FINALIZED 达到 FINALIZED 阈值，不应被抑制");
        verify(repository).findByPaymentIdAndStatus("pay-001", "payment.succeeded");
    }

    @Test
    void finalizingMeetsFinalizingThresholdNotSuppressed() {
        WebhookDeliveryService service = createService("FINALIZING");
        WebhookDeliveryRecord existingRecord = new WebhookDeliveryRecord();
        existingRecord.setDeliveryId("existing");
        when(repository.findByPaymentIdAndStatus("pay-001", "payment.succeeded"))
                .thenReturn(Optional.of(existingRecord));

        WebhookDeliveryRecord result = service.deliver(
                "pay-001", 1L, "https://example.com/webhook",
                payloadWithFinality("FINALIZING"), "payment.succeeded");

        assertNotNull(result, "FINALIZING 达到 FINALIZING 阈值，不应被抑制");
        verify(repository).findByPaymentIdAndStatus("pay-001", "payment.succeeded");
    }

    // ==================== fail-open 场景 ====================

    @Test
    void unknownFinalityStatusStringNotSuppressed() {
        WebhookDeliveryService service = createService("OPTIMISTIC");
        WebhookDeliveryRecord existingRecord = new WebhookDeliveryRecord();
        existingRecord.setDeliveryId("existing");
        when(repository.findByPaymentIdAndStatus("pay-001", "payment.succeeded"))
                .thenReturn(Optional.of(existingRecord));

        WebhookDeliveryRecord result = service.deliver(
                "pay-001", 1L, "https://example.com/webhook",
                payloadWithFinality("INVALID_STATUS"), "payment.succeeded");

        assertNotNull(result, "未知 finalityStatus 字符串不应被抑制（fail-open）");
        verify(repository).findByPaymentIdAndStatus("pay-001", "payment.succeeded");
    }

    @Test
    void payloadWithoutFinalityStatusNotSuppressed() {
        WebhookDeliveryService service = createService("FINALIZED");
        WebhookDeliveryRecord existingRecord = new WebhookDeliveryRecord();
        existingRecord.setDeliveryId("existing");
        when(repository.findByPaymentIdAndStatus("pay-001", "payment.succeeded"))
                .thenReturn(Optional.of(existingRecord));

        Map<String, Object> payload = Map.of("event", "payment.succeeded", "orderId", 1L);

        WebhookDeliveryRecord result = service.deliver(
                "pay-001", 1L, "https://example.com/webhook",
                payload, "payment.succeeded");

        assertNotNull(result, "payload 中没有 finalityStatus 字段不应被抑制");
        verify(repository).findByPaymentIdAndStatus("pay-001", "payment.succeeded");
    }

    // ==================== 默认阈值（测试构造器） ====================

    @Test
    void testConstructorDefaultsToOptimisticThreshold() {
        // 使用 7 参数测试构造器，finalityThreshold 默认 OPTIMISTIC
        WebhookDeliveryService service = new WebhookDeliveryService(
                repository, retryService, signatureService, deadLetterSender,
                restTemplate, "test-secret", urlValidator);

        // UNKNOWN 低于 OPTIMISTIC → 应被抑制
        WebhookDeliveryRecord suppressed = service.deliver(
                "pay-001", 1L, "https://example.com/webhook",
                payloadWithFinality("UNKNOWN"), "payment.succeeded");

        assertNull(suppressed, "测试构造器默认 OPTIMISTIC 阈值，UNKNOWN 应被抑制");
        verify(repository, never()).findByPaymentIdAndStatus(any(), any());
    }

    // ==================== 阈值级别映射验证 ====================

    @Test
    void finalityLevelOrderingIsCorrect() {
        // 验证级别映射：UNKNOWN=0 < OPTIMISTIC=1 < FINALIZING=2 < FINALIZED=3
        // 通过不同阈值下的抑制行为间接验证

        // UNKNOWN vs OPTIMISTIC
        WebhookDeliveryService svcOpt = createService("OPTIMISTIC");
        assertNull(svcOpt.deliver("p1", 1L, "https://example.com/webhook",
                payloadWithFinality("UNKNOWN"), "evt"));

        // UNKNOWN vs FINALIZING
        WebhookDeliveryService svcFin = createService("FINALIZING");
        assertNull(svcFin.deliver("p2", 1L, "https://example.com/webhook",
                payloadWithFinality("UNKNOWN"), "evt"));

        // UNKNOWN vs FINALIZED
        WebhookDeliveryService svcFid = createService("FINALIZED");
        assertNull(svcFid.deliver("p3", 1L, "https://example.com/webhook",
                payloadWithFinality("UNKNOWN"), "evt"));

        // OPTIMISTIC vs FINALIZED
        assertNull(svcFid.deliver("p4", 1L, "https://example.com/webhook",
                payloadWithFinality("OPTIMISTIC"), "evt"));

        // FINALIZING vs FINALIZED
        assertNull(svcFid.deliver("p5", 1L, "https://example.com/webhook",
                payloadWithFinality("FINALIZING"), "evt"));
    }

    // ==================== 非法阈值配置 ====================

    @Test
    void invalidThresholdConfigSuppressesUnknown() {
        // 阈值配置为非法字符串时，fail-secure：默认要求 FINALIZED（最严格）
        WebhookDeliveryService service = createService("INVALID_THRESHOLD");

        // UNKNOWN 低于 FINALIZED → 应被抑制
        WebhookDeliveryRecord result = service.deliver(
                "pay-001", 1L, "https://example.com/webhook",
                payloadWithFinality("UNKNOWN"), "payment.succeeded");

        assertNull(result, "非法阈值配置 fail-secure 为 FINALIZED，UNKNOWN 应被抑制");
        verify(repository, never()).findByPaymentIdAndStatus(any(), any());
    }

    @Test
    void invalidThresholdConfigDefaultsToFinalized() {
        // 阈值配置为非法字符串时，fail-secure：默认要求 FINALIZED（最严格）
        WebhookDeliveryService service = createService("INVALID_THRESHOLD");

        // OPTIMISTIC 低于 FINALIZED → 应被抑制（非法阈值 fail-secure 为 FINALIZED）
        WebhookDeliveryRecord result = service.deliver(
                "pay-001", 1L, "https://example.com/webhook",
                payloadWithFinality("OPTIMISTIC"), "payment.succeeded");

        assertNull(result, "非法阈值配置 fail-secure 为 FINALIZED，OPTIMISTIC 应被抑制");
        verify(repository, never()).findByPaymentIdAndStatus(any(), any());
    }
}