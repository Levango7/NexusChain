package org.nexus.walletsvc.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 钱包侧操作审计事件（P0 补齐 2026-10-11）。
 *
 * <p><b>为什么单独建表</b>：wallet-service 是独立模块、独立库（`nexus_wallet`）；
 * 网关侧审计（`audit_events`）覆盖不到本模块的提现流程。跨服务审计若引入事件总线
 * 代价过大，最省的形态是**本模块自带审计表**（append-only）。</p>
 *
 * <p>覆盖提现四个关键节点：请求 / 审批通过 / 执行成功（带链上哈希）/ 执行失败。
 * 资金出口可追查：谁批的、去哪、多少、是否上链。</p>
 */
@Entity
@Table(name = "wallet_audit_events")
public class WalletAuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_time", nullable = false)
    private LocalDateTime eventTime;

    @Column(name = "action", nullable = false, length = 64)
    private String action;

    @Column(name = "request_id", nullable = false, length = 64)
    private String requestId;

    @Column(name = "to_address", length = 128)
    private String toAddress;

    @Column(name = "amount", length = 64)
    private String amount;

    @Column(name = "currency", length = 16)
    private String currency;

    @Column(name = "detail", length = 2048)
    private String detail;

    // === getters / setters ===

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public LocalDateTime getEventTime() { return eventTime; }
    public void setEventTime(LocalDateTime eventTime) { this.eventTime = eventTime; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getToAddress() { return toAddress; }
    public void setToAddress(String toAddress) { this.toAddress = toAddress; }
    public String getAmount() { return amount; }
    public void setAmount(String amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
}
