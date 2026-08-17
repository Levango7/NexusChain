package org.nexus.signing.audit;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 私钥操作审计事件（P2-F1 完整安全架构）。
 *
 * <p>记录所有涉及私钥访问、签名、MPC 操作的安全事件，由
 * {@link AuditLogService} 写入专用审计日志（结构化 JSON 格式）。</p>
 *
 * <h3>事件类型</h3>
 * <ul>
 *   <li>{@link Type#SIGN_TRANSFER}：签名转账操作（消耗私钥使用配额）</li>
 *   <li>{@link Type#KEYSTORE_ACCESS}：keystore 解密类操作（fromPassword /
 *       modifyPassword / keystoreTo* / prikeyToPubkey）</li>
 *   <li>{@link Type#MPC_OPERATION}：MPC 多签操作（DKG / sign / aggregate）</li>
 *   <li>{@link Type#APPROVAL_REQUEST}：多签审批请求发起</li>
 *   <li>{@link Type#APPROVAL_DECISION}：多签审批决策（通过/拒绝）</li>
 *   <li>{@link Type#AUTH_FAILURE}：鉴权失败（401/403，潜在攻击信号）</li>
 * </ul>
 *
 * <h3>敏感数据保护</h3>
 * <p>本类严格禁止记录以下敏感数据（见任务约束）：
 * <ul>
 *   <li>私钥明文（prikey）</li>
 *   <li>签名内容（signature / r / s 值）</li>
 *   <li>keystore JSON 明文</li>
 *   <li>解密密码</li>
 * </ul>
 * 仅记录操作元数据（who / what / when / where / outcome），
 * 私钥 ID 等标识符使用脱敏后的 hash 或别名。</p>
 *
 * <h3>不可变性</h3>
 * <p>本类为不可变值对象：构造后所有字段不可修改，{@link #getDetails()}
 * 返回不可修改的 Map 视图，确保审计记录写入后不可篡改。</p>
 */
public final class AuditEvent {

    /** 审计事件类型。 */
    public enum Type {
        /** 签名转账操作（/api/v1/transfers/sign、/ClientToTransferAccount）。 */
        SIGN_TRANSFER,
        /** keystore 解密类操作（fromPassword / modifyPassword / keystoreTo* / prikeyToPubkey）。 */
        KEYSTORE_ACCESS,
        /** MPC 多签操作（DKG / sign / aggregate）。 */
        MPC_OPERATION,
        /** 多签审批请求发起。 */
        APPROVAL_REQUEST,
        /** 多签审批决策（通过/拒绝）。 */
        APPROVAL_DECISION,
        /** 鉴权失败（401/403，潜在攻击信号）。 */
        AUTH_FAILURE
    }

    /** 操作结果。 */
    public enum Outcome {
        /** 操作成功。 */
        SUCCESS,
        /** 操作失败（业务错误，非鉴权问题）。 */
        FAILURE,
        /** 操作被拒绝（鉴权 / 审批 / 限额等）。 */
        DENIED
    }

    private final Instant timestamp;
    private final Type type;
    private final Outcome outcome;
    /** 调用方标识（JWT subject，通常为服务名或用户 ID）。 */
    private final String actor;
    /** 来源 IP（X-Forwarded-For 优先，回退 RemoteAddr）。 */
    private final String sourceIp;
    /** 操作目标标识（如交易 hash、密钥 ID、MPC session_id），脱敏后存储。 */
    private final String target;
    /** 附加详情（如 amount、currency、party_index），不可包含敏感数据。 */
    private final Map<String, Object> details;

    private AuditEvent(Instant timestamp, Type type, Outcome outcome,
                       String actor, String sourceIp, String target,
                       Map<String, Object> details) {
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
        this.type = Objects.requireNonNull(type, "type");
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.actor = actor;
        this.sourceIp = sourceIp;
        this.target = target;
        this.details = details == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    /**
     * 创建审计事件构建器。
     *
     * @param type     事件类型
     * @param outcome  操作结果
     * @param actor    调用方标识
     * @return 构建器实例
     */
    public static Builder builder(Type type, Outcome outcome, String actor) {
        return new Builder(type, outcome, actor);
    }

    public Instant getTimestamp() { return timestamp; }
    public Type getType() { return type; }
    public Outcome getOutcome() { return outcome; }
    public String getActor() { return actor; }
    public String getSourceIp() { return sourceIp; }
    public String getTarget() { return target; }
    public Map<String, Object> getDetails() { return details; }

    @Override
    public String toString() {
        return "AuditEvent{timestamp=" + timestamp
                + ", type=" + type
                + ", outcome=" + outcome
                + ", actor=" + actor
                + ", sourceIp=" + sourceIp
                + ", target=" + target
                + ", details=" + details
                + '}';
    }

    /** 审计事件构建器（流式 API）。 */
    public static final class Builder {
        private final Type type;
        private final Outcome outcome;
        private final String actor;
        private String sourceIp;
        private String target;
        private final Map<String, Object> details = new LinkedHashMap<>();

        Builder(Type type, Outcome outcome, String actor) {
            this.type = type;
            this.outcome = outcome;
            this.actor = actor;
        }

        /** 设置来源 IP。 */
        public Builder sourceIp(String ip) {
            this.sourceIp = ip;
            return this;
        }

        /** 设置操作目标标识（脱敏后的 hash / 别名）。 */
        public Builder target(String target) {
            this.target = target;
            return this;
        }

        /** 添加附加详情键值对（不可包含敏感数据）。 */
        public Builder detail(String key, Object value) {
            if (key != null) {
                this.details.put(key, value);
            }
            return this;
        }

        /** 构建不可变 AuditEvent 实例。 */
        public AuditEvent build() {
            return new AuditEvent(
                    Instant.now(),
                    type,
                    outcome,
                    actor,
                    sourceIp,
                    target,
                    details);
        }
    }
}