package org.nexus.signing.approval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 审批请求 JPA 持久化存储（任务 #375）。
 *
 * <p>{@link ApprovalStore} 的数据库实现，基于
 * {@link SigningApprovalRequestRepository}（Spring Data JPA），将审批请求持久化到
 * {@code signing_approval_request} 表。多实例部署时各实例共享同一张表，
 * 消除内存 / 文件存储下审批状态不共享的风险。</p>
 *
 * <h3>设计说明</h3>
 * <ul>
 *   <li><b>upsert 语义</b>：{@link #put} / {@link #save} 先按 requestId 查询，
 *       已存在则复用主键与乐观锁版本做行级更新，否则插入新行。
 *       多实例并发覆写时由 Entity 的 {@code @Version} 乐观锁兜底，
 *       后提交者抛出 {@code ObjectOptimisticLockingFailureException} 而非静默丢更新。</li>
 *   <li><b>集合序列化</b>：approvals / rejections 以 JSON 数组文本列存储，
 *       与行级整体覆写模型匹配（审批请求为不可变值对象，每次变更全量替换）。</li>
 *   <li><b>时间转换</b>：领域对象 {@code Instant} ↔ Entity {@code LocalDateTime}（UTC），
 *       沿用 nexus-wallet-service 的映射范式。</li>
 *   <li><b>异常策略</b>：DB 操作失败直接向上抛出（fail-fast），不吞异常——
 *       审批状态属于资金安全关键路径，静默降级可能导致未授权签名。</li>
 * </ul>
 *
 * <p>本类位于 {@code org.nexus.signing.approval} 包内，以便调用
 * {@link SigningApprovalRequest} 的包私有构造器重建不可变值对象。</p>
 *
 * @since 2.31.0
 */
public class JpaApprovalStore implements ApprovalStore {

    private static final Logger log = LoggerFactory.getLogger(JpaApprovalStore.class);

    /** JSON 序列化器（无状态线程安全，静态共享）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final TypeReference<LinkedHashSet<String>> STRING_SET_TYPE = new TypeReference<>() { };

    private final SigningApprovalRequestRepository repository;

    /**
     * 构造函数。
     *
     * @param repository Spring Data Repository（非空）
     */
    public JpaApprovalStore(SigningApprovalRequestRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
        log.info("JpaApprovalStore 初始化完成（signing_approval_request 表，多实例共享）");
    }

    @Override
    public void put(String requestId, SigningApprovalRequest request) {
        upsert(request);
    }

    @Override
    public void save(String requestId, SigningApprovalRequest request) {
        upsert(request);
    }

    @Override
    public SigningApprovalRequest get(String requestId) {
        if (requestId == null) {
            return null;
        }
        return repository.findByRequestId(requestId).map(JpaApprovalStore::toDomain).orElse(null);
    }

    @Override
    public SigningApprovalRequest remove(String requestId) {
        if (requestId == null) {
            return null;
        }
        SigningApprovalRequestEntity entity = repository.findByRequestId(requestId).orElse(null);
        if (entity == null) {
            return null;
        }
        repository.delete(entity);
        return toDomain(entity);
    }

    @Override
    public Set<Map.Entry<String, SigningApprovalRequest>> entrySet() {
        return repository.findAll().stream()
                .map(entity -> new AbstractMap.SimpleImmutableEntry<>(
                        entity.getRequestId(), toDomain(entity)))
                .collect(Collectors.toSet());
    }

    @Override
    public int size() {
        return (int) Math.min(repository.count(), Integer.MAX_VALUE);
    }

    /**
     * 原子 CAS：基于实体 {@code @Version} 乐观锁的条件状态迁移。
     *
     * <p>实现：读取当前行 → 校验状态等于 expected → setStatus 后 save。
     * 两个并发迁移读到同一版本时，数据库层 {@code UPDATE ... WHERE version=?}
     * 保证仅一个提交成功，后提交者抛 {@link OptimisticLockingFailureException}，
     * 在此捕获并返回 false——与内存实现的 CAS 语义对齐。
     * 若需跨实例严格单次迁移，应配合数据库唯一约束或行锁；
     * 当前乐观锁方案与 v2.38.0 引入的 {@code @Version} 机制一致。</p>
     */
    @Override
    public boolean compareAndTransition(String requestId, SigningApprovalRequest.Status expected,
                                        SigningApprovalRequest.Status to) {
        if (requestId == null) {
            return false;
        }
        SigningApprovalRequestEntity entity = repository.findByRequestId(requestId).orElse(null);
        if (entity == null || entity.getStatus() != expected) {
            return false;
        }
        entity.setStatus(to);
        try {
            repository.save(entity);
            return true;
        } catch (OptimisticLockingFailureException e) {
            log.warn("审批状态 CAS 并发冲突: requestId={}, expected={}→{}, 由其他实例先行迁移",
                    requestId, expected, to);
            return false;
        }
    }

    /**
     * 插入或更新：已存在则保留主键与乐观锁版本做整行覆写，否则插入新行。
     */
    private void upsert(SigningApprovalRequest request) {
        SigningApprovalRequestEntity entity = repository.findByRequestId(request.getRequestId())
                .map(existing -> applyDomain(existing, request))
                .orElseGet(() -> toEntity(request));
        repository.save(entity);
    }

    // --- 领域对象 ↔ Entity 映射 ---

    /** 领域对象 → 新 Entity（insert 路径）。 */
    static SigningApprovalRequestEntity toEntity(SigningApprovalRequest request) {
        SigningApprovalRequestEntity entity = new SigningApprovalRequestEntity();
        return applyDomain(entity, request);
    }

    /** 将领域对象的全部字段覆写到已有 Entity（update 路径，保留 id/version）。 */
    static SigningApprovalRequestEntity applyDomain(SigningApprovalRequestEntity entity,
                                                    SigningApprovalRequest request) {
        entity.setRequestId(request.getRequestId());
        entity.setFromPubkey(request.getFromPubkey());
        entity.setToPubkeyHash(request.getToPubkeyHash());
        entity.setAmount(request.getAmount());
        entity.setCurrency(request.getCurrency());
        entity.setRequiredApprovers(request.getRequiredApprovers());
        entity.setStatus(request.getStatus());
        entity.setApprovalsJson(toJson(request.getApprovals()));
        entity.setRejectionsJson(toJson(request.getRejections()));
        entity.setInitiator(request.getInitiator());
        entity.setCreatedAt(toLocalDateTime(request.getCreatedAt()));
        entity.setDeadline(toLocalDateTime(request.getDeadline()));
        return entity;
    }

    /** Entity → 领域对象（重建不可变值对象，包私有构造器）。 */
    static SigningApprovalRequest toDomain(SigningApprovalRequestEntity entity) {
        return new SigningApprovalRequest(
                entity.getRequestId(),
                entity.getFromPubkey(),
                entity.getToPubkeyHash(),
                entity.getAmount(),
                entity.getCurrency(),
                entity.getRequiredApprovers(),
                toInstant(entity.getCreatedAt()),
                toInstant(entity.getDeadline()),
                entity.getStatus(),
                fromJson(entity.getApprovalsJson()),
                fromJson(entity.getRejectionsJson()),
                entity.getInitiator());
    }

    private static String toJson(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return null; // 空集存 NULL，节省存储
        }
        try {
            return MAPPER.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            // LinkedHashSet<String> 序列化不会失败；防御性包装为非受检异常
            throw new IllegalStateException("审批人集合序列化失败: " + values, e);
        }
    }

    private static Set<String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptySet();
        }
        try {
            return MAPPER.readValue(json, STRING_SET_TYPE);
        } catch (JsonProcessingException e) {
            log.error("审批人集合反序列化失败（返回空集以保持可用性）: {}", e.getMessage());
            return Collections.emptySet();
        }
    }

    private static LocalDateTime toLocalDateTime(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant toInstant(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.toInstant(ZoneOffset.UTC);
    }
}