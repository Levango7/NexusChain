package org.nexus.gateway.audit;

import java.time.Instant;

/**
 * Audit event record. Immutable once written.
 */
public class AuditEvent {
    private long sequence;
    private Instant timestamp;
    private String actor;
    private String action;
    private String resource;
    private String detail;
    private String ipAddress;
    private Long merchantId;

    public long getSequence() { return sequence; }
    public void setSequence(long sequence) { this.sequence = sequence; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
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