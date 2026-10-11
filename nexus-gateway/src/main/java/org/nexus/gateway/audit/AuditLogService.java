package org.nexus.gateway.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A3: Append-only audit log service.
 * Records all sensitive operations (payment, refund, key access, config changes).
 * In production, writes to a dedicated audit database or immutable log store.
 */
@Service
public class AuditLogService {

    private static final Logger auditLog = LoggerFactory.getLogger("AUDIT");
    private final AtomicLong sequence = new AtomicLong(0);

    /**
     * 审计事件仓储（P0 修复 2026-10-11，可空）。
     *
     * <p>此前审计只写 {@code AUDIT} logger（logback 落 {@code audit.json} 文件）：
     * <b>既不落库、也不可查询</b>，资金纠纷时无从查证"谁在什么时候动了钱"。
     * 现同时追加到 {@code audit_events} 表（append-only）。</p>
     *
     * <p>失败语义：落库失败<b>不能阻断业务流程</b>（审计是旁路），但必须 ERROR 留痕，
     * 且 logger 那份始终保留（双写，互为备份）。</p>
     */
    private final AuditEventRepository auditEventRepository;

    // Simple anomaly detection: track operation frequency per merchant
    private final Map<Long, WindowCounter> frequencyTracker = new ConcurrentHashMap<>();
    private static final int ANOMALY_THRESHOLD = 100; // ops per minute

    /** 兼容构造（无仓储）：仅写日志（旧行为）。 */
    public AuditLogService() {
        this(null);
    }

    public AuditLogService(AuditEventRepository auditEventRepository) {
        this.auditEventRepository = auditEventRepository;
    }

    /**
     * Record an audit event.
     */
    public void record(AuditEvent event) {
        event.setSequence(sequence.incrementAndGet());
        event.setTimestamp(Instant.now());

        // Structured audit log (JSON format for log aggregation)
        auditLog.info("{\"seq\":{},\"ts\":\"{}\",\"actor\":\"{}\",\"action\":\"{}\",\"resource\":\"{}\",\"detail\":\"{}\",\"ip\":\"{}\"}",
                event.getSequence(), event.getTimestamp(), event.getActor(),
                event.getAction(), event.getResource(), event.getDetail(), event.getIpAddress());

        // 追加到审计表（P0 修复：此前只写日志文件，不可查询）
        persist(event);

        // Anomaly detection
        checkAnomaly(event);
    }

    /**
     * 落库（旁路，失败不阻断业务，但必须显式报错留痕）。
     */
    private void persist(AuditEvent event) {
        if (auditEventRepository == null) {
            return;
        }
        try {
            auditEventRepository.save(AuditEventEntity.from(event));
        } catch (RuntimeException e) {
            // 双写之一失败：日志仍在，显式 ERROR 便于监控告警
            auditLog.error("AUDIT_PERSIST_FAILED seq={}, action={}, error={}",
                    event.getSequence(), event.getAction(), e.getMessage());
        }
    }

    public void recordPayment(Long merchantId, String orderId, String action, String ip) {
        AuditEvent event = new AuditEvent();
        event.setActor("merchant:" + merchantId);
        event.setAction(action);
        event.setResource("order:" + orderId);
        event.setIpAddress(ip);
        event.setMerchantId(merchantId);
        record(event);
    }

    public void recordKeyAccess(Long merchantId, String operation, String ip) {
        AuditEvent event = new AuditEvent();
        event.setActor("merchant:" + merchantId);
        event.setAction("KEY_" + operation);
        event.setResource("keypair:" + merchantId);
        event.setDetail("Sensitive key material accessed");
        event.setIpAddress(ip);
        event.setMerchantId(merchantId);
        record(event);
    }

    private void checkAnomaly(AuditEvent event) {
        if (event.getMerchantId() == null) return;
        WindowCounter counter = frequencyTracker.computeIfAbsent(event.getMerchantId(), k -> new WindowCounter());
        int count = counter.increment();
        if (count > ANOMALY_THRESHOLD) {
            auditLog.warn("{\"ALERT\":\"ANOMALY\",\"merchant\":{},\"ops_per_min\":{},\"action\":\"{}\"}",
                    event.getMerchantId(), count, event.getAction());
        }
    }

    private static class WindowCounter {
        private volatile long windowStart = System.currentTimeMillis();
        private final AtomicLong count = new AtomicLong(0);

        int increment() {
            long now = System.currentTimeMillis();
            if (now - windowStart > 60_000) {
                windowStart = now;
                count.set(1);
                return 1;
            }
            return (int) count.incrementAndGet();
        }
    }
}