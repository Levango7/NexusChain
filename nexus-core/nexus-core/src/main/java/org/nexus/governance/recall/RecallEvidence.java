package org.nexus.governance.recall;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * 守护人罢免证据实体。
 *
 * <p>记录罢免某位守护人的证据材料，包括作恶行为描述、相关交易/提案 ID、
 * 证据提交人、提交时间等。证据随罢免提案一同提交，进入治理投票流程，
 * 供社区评估罢免合理性。</p>
 *
 * <h3>证据类型</h3>
 * <ul>
 *   <li>{@code MALICIOUS_VETO}：恶意否决正当提案</li>
 *   <li>{@code COLLUSION}：与攻击者串谋</li>
 *   <li>{@code KEY_COMPROMISE}：私钥泄露</li>
 *   <li>{@code INACTIVITY}：长期不参与审核</li>
 *   <li>{@code OTHER}：其他（需在 description 中详述）</li>
 * </ul>
 *
 * @since 1.5
 */
public final class RecallEvidence {

    /** 证据类型枚举 */
    public enum Type {
        /** 恶意否决正当提案 */
        MALICIOUS_VETO,
        /** 与攻击者串谋 */
        COLLUSION,
        /** 私钥泄露 */
        KEY_COMPROMISE,
        /** 长期不参与审核 */
        INACTIVITY,
        /** 其他（需在 description 中详述） */
        OTHER
    }

    /** 证据 ID */
    private final String evidenceId;

    /** 证据类型 */
    private final Type type;

    /** 证据描述（自由文本） */
    private final String description;

    /** 相关交易/提案 ID 列表（佐证材料） */
    private final List<String> relatedIds;

    /** 证据提交人 */
    private final String submittedBy;

    /** 提交时间 */
    private final Instant submittedAt;

    public RecallEvidence(String evidenceId, Type type, String description,
                          List<String> relatedIds, String submittedBy, Instant submittedAt) {
        this.evidenceId = evidenceId;
        this.type = type;
        this.description = description;
        this.relatedIds = relatedIds == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(relatedIds);
        this.submittedBy = submittedBy;
        this.submittedAt = submittedAt;
    }

    public String getEvidenceId() {
        return evidenceId;
    }

    public Type getType() {
        return type;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 返回相关交易/提案 ID 列表（只读）。
     *
     * @return 相关 ID 列表
     */
    public List<String> getRelatedIds() {
        return relatedIds;
    }

    public String getSubmittedBy() {
        return submittedBy;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    @Override
    public String toString() {
        return "RecallEvidence{evidenceId='" + evidenceId + '\''
                + ", type=" + type
                + ", submittedBy='" + submittedBy + '\''
                + ", submittedAt=" + submittedAt + '}';
    }
}