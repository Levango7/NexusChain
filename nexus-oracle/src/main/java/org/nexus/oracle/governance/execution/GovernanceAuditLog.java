package org.nexus.oracle.governance.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.nexus.oracle.governance.ProposalState;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 治理执行审计日志。
 *
 * <p>记录每次治理执行的完整审计信息，包括：
 * <ul>
 *   <li>提案 ID、类型、执行时间、执行结果</li>
 *   <li>操作人（提案发起者）</li>
 *   <li>执行前后的状态变更</li>
 *   <li>执行详情（如转账哈希、目标版本等）</li>
 * </ul>
 *
 * <p><b>GOV-P0-02 安全修复</b>：
 * <ul>
 *   <li>持久化层：通过 {@link GovernanceAuditLogRepository}（JPA）将审计记录持久化到数据库，
 *       进程重启后审计记录不丢失</li>
 *   <li>内存缓存：保留 {@link ConcurrentHashMap} 作为快速查询层，避免频繁查库</li>
 *   <li>哈希链防篡改：每条记录包含前一条记录的 {@code entryHash}（{@code previousHash}），
 *       以及自身的 {@code entryHash}（SHA-256），形成链式结构；
 *       任何对历史记录的修改都会破坏链式结构，可被 {@link #verifyAuditChain(String)} 检测</li>
 * </ul>
 *
 * <p>线程安全：所有内存读写操作通过并发容器保证线程安全；持久化操作由 JPA Repository 保证。
 *
 * <p>向后兼容：若未注入 Repository（如单元测试场景），则仅使用内存存储，哈希链仍然维护。
 *
 * @since 2.0.0
 */
@Slf4j
@Component
public class GovernanceAuditLog {

    /** 哈希链首条记录的 previousHash 占位（64 个 0） */
    public static final String GENESIS_PREVIOUS_HASH = "0".repeat(64);

    /** 内存审计记录存储（proposalId → 该提案的所有审计记录列表，按时间顺序），快速查询层 */
    private final Map<String, List<AuditRecord>> auditLogByProposal = new ConcurrentHashMap<>();

    /** JPA 持久化层（可为 null，表示仅使用内存存储） */
    private final GovernanceAuditLogRepository repository;

    /** JSON 序列化器（用于 result 字段持久化） */
    private final ObjectMapper objectMapper;

    /**
     * 默认构造函数（向后兼容，无持久化层，仅内存存储）。
     */
    public GovernanceAuditLog() {
        this(null, new ObjectMapper());
    }

    /**
     * 构造审计日志（带持久化层，GOV-P0-02）。
     *
     * @param repository   JPA 持久化层（可为 null，表示仅使用内存存储）
     * @param objectMapper JSON 序列化器
     */
    public GovernanceAuditLog(GovernanceAuditLogRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * 记算审计记录的 SHA-256 哈希（GOV-P0-02 哈希链）。
     *
     * <p>哈希输入：{@code proposalId + action + timestamp + executor + result + previousHash}
     *
     * @param proposalId   提案 ID
     * @param action       执行动作
     * @param timestamp    记录时间
     * @param executor     操作人
     * @param result       执行结果 JSON
     * @param previousHash 前一条记录哈希
     * @return 64 hex 字符的 SHA-256 哈希
     */
    static String computeEntryHash(String proposalId, String action, Instant timestamp,
                                   String executor, String result, String previousHash) {
        String raw = String.join("|",
                String.valueOf(proposalId),
                String.valueOf(action),
                String.valueOf(timestamp),
                String.valueOf(executor),
                String.valueOf(result),
                String.valueOf(previousHash));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * 记算审计记录的 SHA-256 哈希（重载，接受 {@link Date}）。
     */
    private static String computeEntryHash(String proposalId, String action, Date timestamp,
                                           String executor, String result, String previousHash) {
        Instant instant = timestamp == null ? null : timestamp.toInstant();
        return computeEntryHash(proposalId, action, instant, executor, result, previousHash);
    }

    /**
     * 序列化执行详情为 JSON 字符串（用于持久化 result 字段）。
     */
    private String serializeResult(ProposalState previousState, ProposalState newState,
                                   boolean success, Map<String, Object> details) {
        Map<String, Object> resultMap = new LinkedHashMap<>();
        resultMap.put("previousState", previousState == null ? null : previousState.name());
        resultMap.put("newState", newState == null ? null : newState.name());
        resultMap.put("success", success);
        resultMap.put("details", details);
        try {
            return objectMapper.writeValueAsString(resultMap);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize audit result, fallback to toString: {}", e.getMessage());
            return resultMap.toString();
        }
    }

    /**
     * 获取提案的最后一条审计记录的 entryHash（用于哈希链衔接）。
     */
    private String getLastEntryHash(String proposalId) {
        if (repository != null) {
            return repository.findFirstByProposalIdOrderByTimestampDesc(proposalId)
                    .map(GovernanceAuditLogEntry::getEntryHash)
                    .orElse(GENESIS_PREVIOUS_HASH);
        }
        List<AuditRecord> records = auditLogByProposal.get(proposalId);
        if (records == null || records.isEmpty()) {
            return GENESIS_PREVIOUS_HASH;
        }
        return records.get(records.size() - 1).getEntryHash();
    }

    /**
     * 记算一次治理执行的审计信息并持久化 + 缓存。
     *
     * @param proposalId    提案 ID
     * @param proposalType  提案类型字符串
     * @param operator      操作人（提案发起者）
     * @param previousState 执行前状态
     * @param newState      执行后状态
     * @param success       执行是否成功
     * @param details       执行详情（如转账哈希、目标版本等，可为 {@code null}）
     * @return 已记录的审计记录
     */
    public AuditRecord record(String proposalId, String proposalType, String operator,
                              ProposalState previousState, ProposalState newState,
                              boolean success, Map<String, Object> details) {
        Map<String, Object> detailMap = details == null ? Map.of() : new LinkedHashMap<>(details);
        Instant now = Instant.now();
        String resultJson = serializeResult(previousState, newState, success, detailMap);
        String previousHash = getLastEntryHash(proposalId);
        String entryHash = computeEntryHash(proposalId, proposalType, now, operator, resultJson, previousHash);

        AuditRecord record = new AuditRecord(
                proposalId, proposalType, operator, previousState, newState,
                success, detailMap, now, previousHash, entryHash);

        // 写入内存缓存
        auditLogByProposal.computeIfAbsent(proposalId, k -> new CopyOnWriteArrayList<>()).add(record);

        // 写入持久化层（GOV-P0-02）
        if (repository != null) {
            try {
                GovernanceAuditLogEntry entry = new GovernanceAuditLogEntry();
                entry.setProposalId(proposalId);
                entry.setAction(proposalType);
                entry.setTimestamp(Date.from(now));
                entry.setExecutor(operator);
                entry.setResult(resultJson);
                entry.setPreviousHash(previousHash);
                entry.setEntryHash(entryHash);
                repository.save(entry);
            } catch (Exception e) {
                log.error("Failed to persist audit log entry (memory cache still updated): proposalId={}, error={}",
                        proposalId, e.getMessage(), e);
            }
        }

        log.info("Governance audit recorded: proposalId={}, type={}, operator={}, {} -> {}, success={}, entryHash={}",
                proposalId, proposalType, operator, previousState, newState, success, entryHash);
        return record;
    }

    /**
     * 查询提案的审计日志。
     *
     * @param proposalId 提案 ID
     * @return 该提案的所有审计记录列表（按时间顺序）；提案不存在记录时返回空列表
     */
    public List<AuditRecord> getAuditLog(String proposalId) {
        if (repository != null) {
            List<GovernanceAuditLogEntry> entries = repository.findByProposalIdOrderByTimestampAsc(proposalId);
            if (entries.isEmpty()) {
                return Collections.emptyList();
            }
            List<AuditRecord> records = new ArrayList<>(entries.size());
            for (GovernanceAuditLogEntry entry : entries) {
                records.add(toAuditRecord(entry));
            }
            return records;
        }
        List<AuditRecord> records = auditLogByProposal.get(proposalId);
        return records == null ? Collections.emptyList() : new ArrayList<>(records);
    }

    /**
     * 将持久化实体转换为 {@link AuditRecord}。
     */
    private AuditRecord toAuditRecord(GovernanceAuditLogEntry entry) {
        ProposalState previousState = null;
        ProposalState newState = null;
        boolean success = false;
        Map<String, Object> details = Map.of();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(entry.getResult(), Map.class);
            Object ps = result.get("previousState");
            if (ps instanceof String s && !s.isEmpty()) {
                previousState = ProposalState.valueOf(s);
            }
            Object ns = result.get("newState");
            if (ns instanceof String s && !s.isEmpty()) {
                newState = ProposalState.valueOf(s);
            }
            Object suc = result.get("success");
            if (suc instanceof Boolean b) {
                success = b;
            }
            Object det = result.get("details");
            if (det instanceof Map<?, ?> m) {
                details = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    details.put(String.valueOf(e.getKey()), e.getValue());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to deserialize audit result, fallback to raw: {}", e.getMessage());
        }
        return new AuditRecord(
                entry.getProposalId(), entry.getAction(), entry.getExecutor(),
                previousState, newState, success, details,
                entry.getTimestamp() == null ? null : entry.getTimestamp().toInstant(),
                entry.getPreviousHash(), entry.getEntryHash());
    }

    /**
     * 查询所有提案的审计日志（按提案 ID 分组）。
     *
     * @return 不可变视图：proposalId → 审计记录列表
     */
    public Map<String, List<AuditRecord>> getAllAuditLogs() {
        if (repository != null) {
            Map<String, List<AuditRecord>> snapshot = new LinkedHashMap<>();
            List<String> proposalIds = repository.findDistinctProposalIds();
            for (String pid : proposalIds) {
                snapshot.put(pid, getAuditLog(pid));
            }
            return Collections.unmodifiableMap(snapshot);
        }
        Map<String, List<AuditRecord>> snapshot = new LinkedHashMap<>();
        auditLogByProposal.forEach((id, records) -> snapshot.put(id, new ArrayList<>(records)));
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * 统计审计记录总数。
     *
     * @return 所有提案的审计记录总数
     */
    public int totalRecords() {
        if (repository != null) {
            return (int) repository.countAllEntries();
        }
        return auditLogByProposal.values().stream().mapToInt(List::size).sum();
    }

    /**
     * 清空所有审计记录（测试 / 重置用，GOV-P1-02）。
     *
     * <p><b>GOV-P1-02 安全修复</b>：此方法已从 {@code public} 改为包级私有
     * （{@code package-private}），防止生产环境中任意代码清除审计日志。
     * 仅同包的测试代码与治理执行内部组件可访问。
     *
     * <p>如需在生产环境重置审计日志，应通过受控的运维接口（如 JMX、Admin API）
     * 并经过权限校验，而非直接调用此方法。
     */
    @VisibleForTesting("GOV-P1-02: package-private to prevent production audit log clearing")
    void clear() {
        auditLogByProposal.clear();
        if (repository != null) {
            try {
                repository.deleteAllInBatch();
            } catch (Exception e) {
                log.warn("Failed to clear persisted audit log: {}", e.getMessage());
            }
        }
        log.debug("Governance audit log cleared");
    }

    /**
     * 验证提案审计日志的哈希链完整性（GOV-P0-02 防篡改）。
     *
     * <p>校验规则：
     * <ul>
     *   <li>首条记录的 previousHash == {@link #GENESIS_PREVIOUS_HASH}</li>
     *   <li>每条记录的 entryHash == SHA-256(proposalId + action + timestamp + executor + result + previousHash)</li>
     *   <li>后续记录的 previousHash == 前一条记录的 entryHash</li>
     * </ul>
     *
     * @param proposalId 提案 ID
     * @return 链完整返回 true；任何环节断裂返回 false
     */
    public boolean verifyAuditChain(String proposalId) {
        List<AuditRecord> records = getAuditLog(proposalId);
        if (records.isEmpty()) {
            log.debug("Audit chain verify: no records for proposalId={}, vacuously true", proposalId);
            return true;
        }
        String expectedPreviousHash = GENESIS_PREVIOUS_HASH;
        for (int i = 0; i < records.size(); i++) {
            AuditRecord r = records.get(i);
            if (!expectedPreviousHash.equals(r.getPreviousHash())) {
                log.warn("Audit chain broken at index {}: proposalId={}, expected previousHash={}, actual={}",
                        i, proposalId, expectedPreviousHash, r.getPreviousHash());
                return false;
            }
            String resultJson = serializeResult(r.getPreviousState(), r.getNewState(),
                    r.isSuccess(), r.getDetails());
            String recomputed = computeEntryHash(r.getProposalId(), r.getProposalType(),
                    r.getTimestamp(), r.getOperator(), resultJson, r.getPreviousHash());
            if (!recomputed.equals(r.getEntryHash())) {
                log.warn("Audit chain hash mismatch at index {}: proposalId={}, expected={}, actual={}",
                        i, proposalId, recomputed, r.getEntryHash());
                return false;
            }
            expectedPreviousHash = r.getEntryHash();
        }
        log.debug("Audit chain verified: proposalId={}, length={}", proposalId, records.size());
        return true;
    }

    /**
     * 审计记录实体。
     *
     * <p>不可变值对象，描述一次治理执行的完整审计轨迹。
     */
    public static final class AuditRecord {
        /** 提案 ID */
        private final String proposalId;
        /** 提案类型 */
        private final String proposalType;
        /** 操作人 */
        private final String operator;
        /** 执行前状态 */
        private final ProposalState previousState;
        /** 执行后状态 */
        private final ProposalState newState;
        /** 执行是否成功 */
        private final boolean success;
        /** 执行详情 */
        private final Map<String, Object> details;
        /** 记录时间 */
        private final Instant timestamp;
        /** 前一条记录的 entryHash（GOV-P0-02 哈希链） */
        private final String previousHash;
        /** 本条记录的 SHA-256 哈希（GOV-P0-02 哈希链） */
        private final String entryHash;

        AuditRecord(String proposalId, String proposalType, String operator,
                    ProposalState previousState, ProposalState newState,
                    boolean success, Map<String, Object> details, Instant timestamp,
                    String previousHash, String entryHash) {
            this.proposalId = proposalId;
            this.proposalType = proposalType;
            this.operator = operator;
            this.previousState = previousState;
            this.newState = newState;
            this.success = success;
            this.details = details;
            this.timestamp = timestamp;
            this.previousHash = previousHash;
            this.entryHash = entryHash;
        }

        /** @return 提案 ID */
        public String getProposalId() {
            return proposalId;
        }

        /** @return 提案类型 */
        public String getProposalType() {
            return proposalType;
        }

        /** @return 操作人 */
        public String getOperator() {
            return operator;
        }

        /** @return 执行前状态 */
        public ProposalState getPreviousState() {
            return previousState;
        }

        /** @return 执行后状态 */
        public ProposalState getNewState() {
            return newState;
        }

        /** @return 执行是否成功 */
        public boolean isSuccess() {
            return success;
        }

        /** @return 执行详情 */
        public Map<String, Object> getDetails() {
            return details;
        }

        /** @return 记录时间 */
        public Instant getTimestamp() {
            return timestamp;
        }

        /** @return 前一条记录的 entryHash */
        public String getPreviousHash() {
            return previousHash;
        }

        /** @return 本条记录的 SHA-256 哈希 */
        public String getEntryHash() {
            return entryHash;
        }

        @Override
        public String toString() {
            return "AuditRecord{proposalId='" + proposalId + "', type='" + proposalType
                    + "', operator='" + operator + "', " + previousState + " -> " + newState
                    + ", success=" + success + ", timestamp=" + timestamp
                    + ", entryHash='" + entryHash + "'}";
        }
    }
}
