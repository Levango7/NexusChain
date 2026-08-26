package org.nexus.signing.approval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link JpaApprovalStore} 单元测试（任务 #378，optional-improvements 高#8）。
 *
 * <p>纯 Mockito 单测：mock {@link SigningApprovalRequestRepository}，
 * 验证 upsert / get / remove / entrySet / size 全部行为与
 * 领域对象 ↔ Entity 的字段映射（含 JSON 集合序列化、Instant↔LocalDateTime(UTC) 转换）。</p>
 *
 * <p>断言以 JpaApprovalStore 实际实现为准：</p>
 * <ul>
 *   <li>upsert 已存在时复用原 Entity（保留 id / version）</li>
 *   <li>approvals/rejections 以 Jackson JSON 数组文本存储，空集存 NULL</li>
 *   <li>remove 通过 repository.delete(entity) 删除（非 deleteById）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class JpaApprovalStoreTest {

    private static final String REQUEST_ID = "req-0001";
    private static final Instant CREATED_AT = Instant.parse("2026-01-15T08:00:00Z");
    private static final Instant DEADLINE = Instant.parse("2026-01-15T09:00:00Z");
    private static final BigDecimal AMOUNT = new BigDecimal("123.456");

    @Mock
    private SigningApprovalRequestRepository repository;

    private JpaApprovalStore store;

    @BeforeEach
    void setUp() {
        // @Mock 字段由 MockitoExtension 在 beforeEach 阶段注入后才能构造被测对象
        store = new JpaApprovalStore(repository);
    }

    // --- put：新实体插入 ---

    @Test
    @DisplayName("put 新请求：findByRequestId 为空 → save 插入新实体且全字段映射正确")
    void putNewRequestInsertsEntityWithAllFields() {
        when(repository.findByRequestId(REQUEST_ID)).thenReturn(Optional.empty());

        store.put(REQUEST_ID, sampleRequest());

        ArgumentCaptor<SigningApprovalRequestEntity> captor =
                ArgumentCaptor.forClass(SigningApprovalRequestEntity.class);
        verify(repository).save(captor.capture());
        SigningApprovalRequestEntity saved = captor.getValue();

        assertEquals(REQUEST_ID, saved.getRequestId());
        assertEquals("from-pubkey-hex", saved.getFromPubkey());
        assertEquals("to-pubkey-hash", saved.getToPubkeyHash());
        assertEquals(0, AMOUNT.compareTo(saved.getAmount()));
        assertEquals("USDT", saved.getCurrency());
        assertEquals(2, saved.getRequiredApprovers());
        assertEquals(SigningApprovalRequest.Status.PENDING, saved.getStatus());
        // LinkedHashSet 保序 → JSON 数组 ["alice","bob"]；空集存 NULL
        assertEquals("[\"alice\",\"bob\"]", saved.getApprovalsJson());
        assertNull(saved.getRejectionsJson());
        assertEquals("initiator-1", saved.getInitiator());
        assertEquals(LocalDateTime.ofInstant(CREATED_AT, ZoneOffset.UTC), saved.getCreatedAt());
        assertEquals(LocalDateTime.ofInstant(DEADLINE, ZoneOffset.UTC), saved.getDeadline());
    }

    // --- save：已存在则行级更新并保留 id/version ---

    @Test
    @DisplayName("save 更新场景：findByRequestId 有值 → 复用原实体，保留 id 与乐观锁 version")
    void saveExistingRequestKeepsIdAndVersion() {
        SigningApprovalRequestEntity existing = sampleEntity();
        when(repository.findByRequestId(REQUEST_ID)).thenReturn(Optional.of(existing));

        store.save(REQUEST_ID, sampleRequest());

        ArgumentCaptor<SigningApprovalRequestEntity> captor =
                ArgumentCaptor.forClass(SigningApprovalRequestEntity.class);
        verify(repository).save(captor.capture());
        SigningApprovalRequestEntity saved = captor.getValue();

        assertSame(existing, saved);
        assertEquals(42L, saved.getId());
        assertEquals(7L, saved.getVersion());
    }

    // --- get：Entity → 领域对象往返 ---

    @Test
    @DisplayName("get 命中：Entity 重建为领域对象且全字段一致（JSON 反序列化 + UTC 时间转换）")
    void getRebuildsDomainFromEntity() {
        when(repository.findByRequestId(REQUEST_ID)).thenReturn(Optional.of(sampleEntity()));

        SigningApprovalRequest result = store.get(REQUEST_ID);

        assertNotNull(result);
        assertEquals(REQUEST_ID, result.getRequestId());
        assertEquals("from-pubkey-hex", result.getFromPubkey());
        assertEquals("to-pubkey-hash", result.getToPubkeyHash());
        assertEquals(0, AMOUNT.compareTo(result.getAmount()));
        assertEquals("USDT", result.getCurrency());
        assertEquals(2, result.getRequiredApprovers());
        assertEquals(SigningApprovalRequest.Status.PENDING, result.getStatus());
        assertEquals(CREATED_AT, result.getCreatedAt());
        assertEquals(DEADLINE, result.getDeadline());
        assertEquals(Set.of("alice", "bob"), result.getApprovals());
        assertEquals(Set.of("carol"), result.getRejections());
        assertEquals("initiator-1", result.getInitiator());
    }

    @Test
    @DisplayName("get 未命中：返回 null")
    void getMissingReturnsNull() {
        when(repository.findByRequestId(REQUEST_ID)).thenReturn(Optional.empty());

        assertNull(store.get(REQUEST_ID));
    }

    @Test
    @DisplayName("get 入参 null：直接返回 null 且不触达数据库")
    void getNullIdReturnsNullWithoutDbCall() {
        assertNull(store.get(null));
        verifyNoInteractions(repository);
    }

    // --- remove ---

    @Test
    @DisplayName("remove 命中：delete(entity) 并返回对应领域对象")
    void removeExistingDeletesAndReturnsDomain() {
        SigningApprovalRequestEntity entity = sampleEntity();
        when(repository.findByRequestId(REQUEST_ID)).thenReturn(Optional.of(entity));

        SigningApprovalRequest removed = store.remove(REQUEST_ID);

        assertNotNull(removed);
        assertEquals(REQUEST_ID, removed.getRequestId());
        verify(repository).delete(entity);
    }

    @Test
    @DisplayName("remove 未命中：返回 null 且不执行删除")
    void removeMissingReturnsNull() {
        when(repository.findByRequestId(REQUEST_ID)).thenReturn(Optional.empty());

        assertNull(store.remove(REQUEST_ID));
        verify(repository, never()).delete(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("remove 入参 null：直接返回 null 且不触达数据库")
    void removeNullIdReturnsNullWithoutDbCall() {
        assertNull(store.remove(null));
        verifyNoInteractions(repository);
    }

    // --- entrySet / size ---

    @Test
    @DisplayName("entrySet：返回全部条目的 requestId → 领域对象映射")
    void entrySetReturnsAllEntries() {
        SigningApprovalRequestEntity entityA = sampleEntity();
        entityA.setRequestId("req-A");
        SigningApprovalRequestEntity entityB = sampleEntity();
        entityB.setRequestId("req-B");
        when(repository.findAll()).thenReturn(List.of(entityA, entityB));

        Set<Map.Entry<String, SigningApprovalRequest>> entries = store.entrySet();

        assertEquals(2, entries.size());
        Map<String, SigningApprovalRequest> byRequestId = entries.stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertEquals(Set.of("req-A", "req-B"), byRequestId.keySet());
        assertEquals("from-pubkey-hex", byRequestId.get("req-A").getFromPubkey());
        assertEquals("from-pubkey-hex", byRequestId.get("req-B").getFromPubkey());
    }

    @Test
    @DisplayName("size：返回 repository.count()")
    void sizeReturnsCount() {
        when(repository.count()).thenReturn(5L);

        assertEquals(5, store.size());
    }

    // --- 测试数据构造辅助 ---

    /** 构造标准领域对象：approvals={alice,bob}，rejections=空集。 */
    private static SigningApprovalRequest sampleRequest() {
        return new SigningApprovalRequest(
                REQUEST_ID,
                "from-pubkey-hex",
                "to-pubkey-hash",
                AMOUNT,
                "USDT",
                2,
                CREATED_AT,
                DEADLINE,
                SigningApprovalRequest.Status.PENDING,
                new LinkedHashSet<>(List.of("alice", "bob")),
                new LinkedHashSet<>(),
                "initiator-1");
    }

    /** 构造标准 Entity：id=42 / version=7，rejectionsJson 含 carol 以覆盖反序列化分支。 */
    private static SigningApprovalRequestEntity sampleEntity() {
        SigningApprovalRequestEntity entity = new SigningApprovalRequestEntity();
        entity.setId(42L);
        entity.setVersion(7L);
        entity.setRequestId(REQUEST_ID);
        entity.setFromPubkey("from-pubkey-hex");
        entity.setToPubkeyHash("to-pubkey-hash");
        entity.setAmount(AMOUNT);
        entity.setCurrency("USDT");
        entity.setRequiredApprovers(2);
        entity.setStatus(SigningApprovalRequest.Status.PENDING);
        entity.setApprovalsJson("[\"alice\",\"bob\"]");
        entity.setRejectionsJson("[\"carol\"]");
        entity.setInitiator("initiator-1");
        entity.setCreatedAt(LocalDateTime.ofInstant(CREATED_AT, ZoneOffset.UTC));
        entity.setDeadline(LocalDateTime.ofInstant(DEADLINE, ZoneOffset.UTC));
        return entity;
    }
}