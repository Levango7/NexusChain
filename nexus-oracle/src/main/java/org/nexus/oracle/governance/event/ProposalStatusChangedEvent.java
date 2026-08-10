package org.nexus.oracle.governance.event;

import org.nexus.oracle.governance.Proposal;
import org.nexus.oracle.governance.ProposalState;

import java.time.Instant;

/**
 * 提案状态变更事件。
 *
 * <p>当提案状态发生流转（如 PENDING → ACTIVE → PASSED → EXECUTED）时发布。
 * {@link org.nexus.oracle.governance.execution.GovernanceExecutionDispatcher} 监听此事件，
 * 当状态变更为 {@link ProposalState#PASSED} 时触发治理执行调度。
 *
 * <p>事件载荷包含：
 * <ul>
 *   <li>{@code proposalId} — 提案 ID</li>
 *   <li>{@code previousState} — 变更前状态</li>
 *   <li>{@code newState} — 变更后状态</li>
 *   <li>{@code proposal} — 提案对象（便于监听方获取完整上下文）</li>
 *   <li>{@code timestamp} — 事件发布时间</li>
 *   <li>{@code source} — 事件来源标识（用于 GOV-P0-01 事件源认证）</li>
 * </ul>
 *
 * <p><b>GOV-P0-01 安全修复</b>：引入 {@code source} 字段标识事件发布者，
 * 消费方（{@link org.nexus.oracle.governance.execution.GovernanceExecutionDispatcher}）
 * 通过白名单校验来源是否受信任，避免任意组件伪造事件触发治理执行。
 *
 * @since 2.0.0
 */
public class ProposalStatusChangedEvent {

    /** 默认受信任的事件来源标识（GovernanceService） */
    public static final String DEFAULT_SOURCE = "governance-service";

    /** 提案 ID */
    private final String proposalId;

    /** 变更前状态 */
    private final ProposalState previousState;

    /** 变更后状态 */
    private final ProposalState newState;

    /** 提案对象 */
    private final Proposal proposal;

    /** 事件发布时间 */
    private final Instant timestamp;

    /** 事件来源标识（用于白名单校验，GOV-P0-01） */
    private final String source;

    /**
     * 构造提案状态变更事件（向后兼容版本，source 默认为 {@link #DEFAULT_SOURCE}）。
     *
     * @param proposalId    提案 ID
     * @param previousState 变更前状态（可为 {@code null}，表示初始创建）
     * @param newState      变更后状态
     * @param proposal      提案对象
     */
    public ProposalStatusChangedEvent(String proposalId, ProposalState previousState,
                                      ProposalState newState, Proposal proposal) {
        this(proposalId, previousState, newState, proposal, DEFAULT_SOURCE);
    }

    /**
     * 构造提案状态变更事件（显式指定来源，GOV-P0-01）。
     *
     * <p>注意：显式传入 {@code null} 或空字符串将保留原值（不替换为默认值），
     * 消费方（{@link org.nexus.oracle.governance.execution.GovernanceExecutionDispatcher}）
     * 会拒绝 null/blank 来源的事件。如需使用默认受信任来源，请使用 4 参数构造函数。
     *
     * @param proposalId    提案 ID
     * @param previousState 变更前状态（可为 {@code null}，表示初始创建）
     * @param newState      变更后状态
     * @param proposal      提案对象
     * @param source        事件来源标识（如 "governance-service"），用于白名单校验；
     *                       null/blank 表示未认证来源，将被拒绝
     */
    public ProposalStatusChangedEvent(String proposalId, ProposalState previousState,
                                      ProposalState newState, Proposal proposal, String source) {
        this.proposalId = proposalId;
        this.previousState = previousState;
        this.newState = newState;
        this.proposal = proposal;
        this.source = source;
        this.timestamp = Instant.now();
    }

    /** @return 提案 ID */
    public String getProposalId() {
        return proposalId;
    }

    /** @return 变更前状态 */
    public ProposalState getPreviousState() {
        return previousState;
    }

    /** @return 变更后状态 */
    public ProposalState getNewState() {
        return newState;
    }

    /** @return 提案对象 */
    public Proposal getProposal() {
        return proposal;
    }

    /** @return 事件发布时间 */
    public Instant getTimestamp() {
        return timestamp;
    }

    /**
     * @return 事件来源标识（用于白名单校验，GOV-P0-01）
     */
    public String getSource() {
        return source;
    }

    @Override
    public String toString() {
        return "ProposalStatusChangedEvent{proposalId='" + proposalId + "', "
                + previousState + " -> " + newState
                + ", source='" + source + "', timestamp=" + timestamp + '}';
    }
}
