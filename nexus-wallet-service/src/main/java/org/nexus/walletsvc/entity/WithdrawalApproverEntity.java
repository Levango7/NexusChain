package org.nexus.walletsvc.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 提现审批人 Entity，映射 {@code withdrawal_approvers} 表。
 *
 * <p>替代 SDK DTO {@link org.nexus.sdk.wallet.WithdrawalRequest#getApprovers()}
 * （{@code List<String>}，内存中嵌在 request 内）的持久化形态，一对多关联到
 * {@link WithdrawalRequestEntity}（通过 {@code request_id} 字符串外键关联，
 * 设计文档 §4.1.4 / §4.2.1）。</p>
 *
 * <p>唯一约束 {@code uk_request_approver (request_id, approver_id)} 防止同一审批人
 * 对同一请求重复审批，由数据库强制保证。</p>
 *
 * <p>不使用 JPA {@code @ManyToOne} 反向关联到 {@link WithdrawalRequestEntity}，
 * 保持 Entity 轻量，关联通过 {@code request_id} 字符串字段表达，与表结构一致。</p>
 */
@Entity
@Table(name = "withdrawal_approvers")
public class WithdrawalApproverEntity {

    /** 自增主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联提现请求 ID（外键 {@code fk_approver_request}）。 */
    @Column(name = "request_id", nullable = false, length = 64)
    private String requestId;

    /** 审批人 ID。 */
    @Column(name = "approver_id", nullable = false, length = 64)
    private String approverId;

    /** 审批时间。 */
    @Column(name = "approved_at", nullable = false)
    private LocalDateTime approvedAt;

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getApproverId() { return approverId; }
    public void setApproverId(String approverId) { this.approverId = approverId; }

    public LocalDateTime getApprovedAt() { return approvedAt; }
    public void setApprovedAt(LocalDateTime approvedAt) { this.approvedAt = approvedAt; }
}