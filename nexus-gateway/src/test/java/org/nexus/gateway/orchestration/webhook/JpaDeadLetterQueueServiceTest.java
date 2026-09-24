package org.nexus.gateway.orchestration.webhook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link JpaDeadLetterQueueService} 单元测试（Wave 8-A5）。
 *
 * <p>验证死信队列持久化核心逻辑：
 * <ul>
 *   <li>sendToDeadLetter：消息持久化到 DB</li>
 *   <li>listPendingReplay：列出待重投记录</li>
 *   <li>markReplayed / markArchived / markPendingReplay：状态流转</li>
 *   <li>findByEventId：按事件 ID 查询</li>
 *   <li>countPendingReplay：统计待重投数量</li>
 * </ul>
 */
class JpaDeadLetterQueueServiceTest {

    private DeadLetterRecordRepository repository;
    private JpaDeadLetterQueueService jpaDlqService;

    @BeforeEach
    void setUp() {
        repository = mock(DeadLetterRecordRepository.class);
        jpaDlqService = new JpaDeadLetterQueueService(repository);
    }

    private DeadLetterMessage sampleMessage(String deliveryId, String paymentId) {
        return new DeadLetterMessage(
                deliveryId, paymentId, 1001L,
                "https://merchant.example/webhook",
                "{\"event\":\"payment.succeeded\",\"payment_id\":\"" + paymentId + "\"}",
                "abc123signature",
                "Connection refused",
                5,
                Instant.now().minusSeconds(300),
                Instant.now(),
                Instant.now()
        );
    }

    @Test
    @DisplayName("sendToDeadLetter: 消息持久化到 DB")
    void sendToDeadLetter_persistsToDb() {
        when(repository.save(any(DeadLetterRecord.class)))
                .thenAnswer(invocation -> {
                    DeadLetterRecord r = invocation.getArgument(0);
                    r.setId(1L);
                    return r;
                });

        jpaDlqService.sendToDeadLetter(sampleMessage("d1", "p1"));

        verify(repository).save(any(DeadLetterRecord.class));
    }

    @Test
    @DisplayName("sendToDeadLetter: null 消息抛 IllegalArgumentException")
    void sendToDeadLetter_nullThrows() {
        assertThrows(IllegalArgumentException.class, () -> jpaDlqService.sendToDeadLetter(null));
    }

    @Test
    @DisplayName("sendToDeadLetter: 正确映射所有字段")
    void sendToDeadLetter_mapsAllFields() {
        DeadLetterMessage msg = sampleMessage("delivery_001", "pay_001");
        when(repository.save(any(DeadLetterRecord.class)))
                .thenAnswer(invocation -> {
                    DeadLetterRecord r = invocation.getArgument(0);
                    r.setId(1L);
                    return r;
                });

        jpaDlqService.sendToDeadLetter(msg);

        verify(repository).save(argThat(record -> {
            assertEquals("https://merchant.example/webhook", record.getWebhookUrl());
            assertEquals("delivery_001", record.getEventId());
            assertEquals(msg.getPayload(), record.getPayload());
            assertEquals("Connection refused", record.getErrorMessage());
            assertEquals(5, record.getRetryCount());
            assertEquals(DeadLetterRecordStatus.PENDING_REPLAY, record.getStatus());
            assertNotNull(record.getCreatedAt());
            assertNotNull(record.getLastRetryAt());
            return true;
        }));
    }

    @Test
    @DisplayName("listPendingReplay: 返回待重投记录")
    void listPendingReplay_returnsPendingRecords() {
        DeadLetterRecord r1 = new DeadLetterRecord();
        r1.setId(1L);
        r1.setStatus(DeadLetterRecordStatus.PENDING_REPLAY);
        DeadLetterRecord r2 = new DeadLetterRecord();
        r2.setId(2L);
        r2.setStatus(DeadLetterRecordStatus.PENDING_REPLAY);

        when(repository.findByStatus(DeadLetterRecordStatus.PENDING_REPLAY))
                .thenReturn(Arrays.asList(r1, r2));

        List<DeadLetterRecord> result = jpaDlqService.listPendingReplay();

        assertEquals(2, result.size());
        verify(repository).findByStatus(DeadLetterRecordStatus.PENDING_REPLAY);
    }

    @Test
    @DisplayName("markReplayed: 标记为已重投")
    void markReplayed_updatesStatus() {
        DeadLetterRecord record = new DeadLetterRecord();
        record.setId(1L);
        record.setStatus(DeadLetterRecordStatus.PENDING_REPLAY);

        when(repository.findById(1L)).thenReturn(Optional.of(record));
        when(repository.save(any(DeadLetterRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        jpaDlqService.markReplayed(1L);

        verify(repository).save(argThat(r -> {
            assertEquals(DeadLetterRecordStatus.REPLAYED, r.getStatus());
            assertNotNull(r.getLastRetryAt());
            return true;
        }));
    }

    @Test
    @DisplayName("markArchived: 标记为已归档")
    void markArchived_updatesStatus() {
        DeadLetterRecord record = new DeadLetterRecord();
        record.setId(1L);
        record.setStatus(DeadLetterRecordStatus.PENDING_REPLAY);

        when(repository.findById(1L)).thenReturn(Optional.of(record));
        when(repository.save(any(DeadLetterRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        jpaDlqService.markArchived(1L);

        verify(repository).save(argThat(r ->
                r.getStatus() == DeadLetterRecordStatus.ARCHIVED));
    }

    @Test
    @DisplayName("markPendingReplay: 重投失败后重新标记为待重投")
    void markPendingReplay_updatesStatus() {
        DeadLetterRecord record = new DeadLetterRecord();
        record.setId(1L);
        record.setStatus(DeadLetterRecordStatus.REPLAYED);

        when(repository.findById(1L)).thenReturn(Optional.of(record));
        when(repository.save(any(DeadLetterRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        jpaDlqService.markPendingReplay(1L);

        verify(repository).save(argThat(r -> {
            assertEquals(DeadLetterRecordStatus.PENDING_REPLAY, r.getStatus());
            assertNotNull(r.getLastRetryAt());
            return true;
        }));
    }

    @Test
    @DisplayName("findByEventId: 按事件 ID 查询")
    void findByEventId_returnsRecords() {
        DeadLetterRecord record = new DeadLetterRecord();
        record.setId(1L);
        record.setEventId("delivery_001");

        when(repository.findByEventId("delivery_001"))
                .thenReturn(List.of(record));

        List<DeadLetterRecord> result = jpaDlqService.findByEventId("delivery_001");

        assertEquals(1, result.size());
        assertEquals("delivery_001", result.get(0).getEventId());
    }

    @Test
    @DisplayName("countPendingReplay: 统计待重投数量")
    void countPendingReplay_returnsCount() {
        when(repository.countByStatus(DeadLetterRecordStatus.PENDING_REPLAY))
                .thenReturn(5L);

        long count = jpaDlqService.countPendingReplay();

        assertEquals(5L, count);
    }

    @Test
    @DisplayName("markReplayed: 记录不存在时不抛异常")
    void markReplayed_nonExistentNoError() {
        when(repository.findById(999L)).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> jpaDlqService.markReplayed(999L));
        verify(repository, never()).save(any());
    }
}