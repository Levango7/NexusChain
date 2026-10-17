package org.nexus.gateway.audit;

import org.nexus.gateway.security.audit.AuditEvent;
import org.nexus.gateway.security.audit.SecurityAuditSink;
import org.springframework.stereotype.Component;

/**
 * {@link SecurityAuditSink} 的落库实现：把安全审计事件写进通用审计表 {@code audit_events}，
 * 使其可查询（此前安全事件只有一行进不了 appender 的日志）。
 *
 * <p>映射：{@code actor=system}（V96 注释里承认 {@code merchant:<id> / system / admin:<id>} 三档，
 * 安全观测没有人操作）、{@code action=SECURITY_<AuditEventType>}、{@code resource=paymentOrder:<id>}
 * （无订单时用 {@code merchant:<id>}，两者都无则退化成事件名）、{@code detail} 装
 * operationType/result/safeDetails 三件。</p>
 *
 * <p>三个字符串都按 V96 列宽（actor 128 / action 64 / resource 256 / detail 1024）写前截断：
 * 超宽会插入失败，而 {@link AuditLogService#persist} 是"捕获后只记
 * {@code AUDIT_PERSIST_FAILED}"的旁路——真超长的表现是"看着写进去了其实没有"，日志与门禁都不会红。</p>
 *
 * <p>为什么这个类在 {@code audit} 切片而不是 {@code security.audit}：反过来的话
 * {@code security → audit} 与既有 {@code audit → config → security} 会拼成 ArchUnit
 * 禁止的切片环（实测红过一次）。</p>
 */
@Component
public class AuditStoreSecuritySink implements SecurityAuditSink {

    /** {@code audit_events.action} 的类别前缀（列宽 64，见 V96）。 */
    static final String ACTION_PREFIX = "SECURITY_";

    private static final int MAX_ACTION = 64;
    private static final int MAX_RESOURCE = 256;
    private static final int MAX_DETAIL = 1024;

    private final AuditLogService auditLogService;

    public AuditStoreSecuritySink(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @Override
    public void persist(AuditEvent event) {
        org.nexus.gateway.audit.AuditEvent row = new org.nexus.gateway.audit.AuditEvent();
        row.setActor("system");
        row.setAction(truncate(ACTION_PREFIX
                + (event.getSecurityEventType() == null ? "UNCLASSIFIED" : event.getSecurityEventType().name()),
                MAX_ACTION));
        row.setResource(truncate(resourceOf(event), MAX_RESOURCE));
        row.setDetail(truncate(detailOf(event), MAX_DETAIL));
        row.setMerchantId(event.getMerchantId());
        auditLogService.record(row);
    }

    private static String resourceOf(AuditEvent event) {
        if (event.getPaymentOrderId() != null) {
            return "paymentOrder:" + event.getPaymentOrderId();
        }
        if (event.getMerchantId() != null) {
            return "merchant:" + event.getMerchantId();
        }
        return "security-event:" + event.getSecurityEventType();
    }

    private static String detailOf(AuditEvent event) {
        StringBuilder sb = new StringBuilder();
        if (event.getOperationType() != null) {
            sb.append("op=").append(event.getOperationType()).append(' ');
        }
        if (event.getResult() != null) {
            sb.append("result=").append(event.getResult()).append(' ');
        }
        if (event.getSafeDetails() != null) {
            sb.append(event.getSafeDetails());
        }
        return sb.toString().trim();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
