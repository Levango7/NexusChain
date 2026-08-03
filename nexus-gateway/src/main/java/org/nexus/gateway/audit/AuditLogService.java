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

    // Simple anomaly detection: track operation frequency per merchant
    private final Map<Long, WindowCounter> frequencyTracker = new ConcurrentHashMap<>();
    private static final int ANOMALY_THRESHOLD = 100; // ops per minute

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

        // Anomaly detection
        checkAnomaly(event);
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