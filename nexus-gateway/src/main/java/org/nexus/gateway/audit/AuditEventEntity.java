package org.nexus.gateway.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 审计事件（落库实体，P0 修复 2026-10-11）。
 *
 * <p><b>背景</b>：原 {@link AuditEvent} 是普通 POJO，{@code AuditLogService.record()}
 * 只把它写进 {@code AUDIT} logger（logback 落 {@code audit.json} 文件）——<b>既不落库、
 * 也不可查询</b>，且支付/退款/提现等<b>资金主链路零覆盖</b>（只有合规与一个死代码
 * 调度在写）。资金纠纷时"想查谁在什么时候动了钱"无从查起。</p>
 *
 * <p>本类把审计事件追加到数据库表（append-only：只插入、不更新、不删除），
 * 业务字段与 {@link AuditEvent} 对齐，另加落库时间与可查询索引。</p>
 */
@Entity
@Table(name = "audit_events", indexes = {
        @Index(name = "idx_audit_merchant", columnList = "merchantId"),
        @Index(name = "idx_audit_action", columnList = "action"),
        @Index(name = "idx_audit_ts", columnList = "eventTime")
})
public class AuditEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 进程内单调序号（与原日志序号一致，便于比对）。 */
    @Column(name = "sequence_no", nullable = false)
    private long sequence;

    @Column(name = "event_time", nullable = false)
    private Instant eventTime;

    @Column(name = "actor", length = 128)
    private String actor;

    @Column(name = "action", nullable = false, length = 64)
    private String action;

    @Column(name = "resource", length = 256)
    private String resource;

    @Column(name = "detail", length = 1024)
    private String detail;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "merchant_id")
    private Long merchantId;

    public static AuditEventEntity from(AuditEvent e) {
        AuditEventEntity entity = new AuditEventEntity();
        entity.sequence = e.getSequence();
        entity.eventTime = e.getTimestamp() != null ? e.getTimestamp() : Instant.now();
        entity.actor = e.getActor();
        entity.action = e.getAction();
        entity.resource = e.getResource();
        entity.detail = e.getDetail();
        entity.ipAddress = e.getIpAddress();
        entity.merchantId = e.getMerchantId();
        return entity;
    }

    // === getters / setters ===

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public long getSequence() { return sequence; }
    public void setSequence(long sequence) { this.sequence = sequence; }
    public Instant getEventTime() { return eventTime; }
    public void setEventTime(Instant eventTime) { this.eventTime = eventTime; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getResource() { return resource; }
    public void setResource(String resource) { this.resource = resource; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
}
