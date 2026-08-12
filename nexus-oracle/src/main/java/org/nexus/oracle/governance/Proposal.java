package org.nexus.oracle.governance;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * 治理提案实体。
 *
 * <p><b>GOV-P1-01 安全修复</b>：{@code state} 字段的公开 setter 已移除，
 * 状态变更必须通过 {@link #transitionTo(ProposalState)} 方法进行，
 * 该方法校验状态转换合法性，非法转换抛出 {@link IllegalStateException}。
 * 初始状态设置通过包级 {@link #initState(ProposalState)} 方法（仅同包
 * {@code GovernanceService} 可调用）。
 *
 * <p>合法状态转换路径：
 * <pre>
 *   PENDING    → ACTIVE, CANCELED
 *   ACTIVE     → PASSED, REJECTED, CANCELED
 *   PASSED     → EXECUTED, EXECUTION_FAILED, CANCELED
 *   REJECTED   → CANCELED
 *   EXECUTION_FAILED → PASSED, EXECUTED (重试)
 *   EXECUTED   → (终态)
 *   CANCELED   → (终态)
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Proposal implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 提案类型 */
    public enum Type {
        /** 链上参数调整（如出块间隔 / 费率） */
        PARAMETER_CHANGE,
        /** 软件升级（节点版本切换） */
        SOFTWARE_UPGRADE,
        /** 国库支出 */
        TREASURY_SPEND,
        /** 验证者集变更（新增/移除共识验证者，NexFinality M_gov 连接轴） */
        VALIDATOR_SET_CHANGE
    }

    /**
     * 合法状态转换矩阵（GOV-P1-01）。
     *
     * <p>key = 当前状态，value = 可转换到的目标状态集合。
     */
    private static final Map<ProposalState, Set<ProposalState>> LEGAL_TRANSITIONS;

    static {
        LEGAL_TRANSITIONS = new EnumMap<>(ProposalState.class);
        LEGAL_TRANSITIONS.put(ProposalState.PENDING,
                Set.of(ProposalState.ACTIVE, ProposalState.CANCELED));
        LEGAL_TRANSITIONS.put(ProposalState.ACTIVE,
                Set.of(ProposalState.PASSED, ProposalState.REJECTED, ProposalState.CANCELED));
        LEGAL_TRANSITIONS.put(ProposalState.PASSED,
                Set.of(ProposalState.EXECUTED, ProposalState.EXECUTION_FAILED, ProposalState.CANCELED));
        LEGAL_TRANSITIONS.put(ProposalState.REJECTED,
                Set.of(ProposalState.CANCELED));
        LEGAL_TRANSITIONS.put(ProposalState.EXECUTION_FAILED,
                Set.of(ProposalState.PASSED, ProposalState.EXECUTED));
        LEGAL_TRANSITIONS.put(ProposalState.EXECUTED, Set.of());
        LEGAL_TRANSITIONS.put(ProposalState.CANCELED, Set.of());
    }

    /** 提案唯一标识 */
    @JsonProperty("proposalId")
    private String proposalId;

    /** 提案标题 */
    @JsonProperty("title")
    private String title;

    /** 提案描述 */
    @JsonProperty("description")
    private String description;

    /** 提案类型 */
    @JsonProperty("type")
    private Type type;

    /**
     * 当前状态。
     *
     * <p>GOV-P1-01：公开 setter 已移除，状态变更须通过
     * {@link #transitionTo(ProposalState)} 方法进行合法转换。
     */
    @JsonProperty("state")
    @Setter(AccessLevel.NONE)
    private ProposalState state;

    /** 投票期开始时间 */
    @JsonProperty("votingStart")
    private Instant votingStart;

    /** 投票期时长 */
    @JsonProperty("votingPeriod")
    private Duration votingPeriod;

    /** 通过后到执行之间的延迟 */
    @JsonProperty("executionDelay")
    private Duration executionDelay;

    /** 提案参数（类型相关，如 PARAMETER_CHANGE 的键值对、TREASURY_SPEND 的金额与目标） */
    @JsonProperty("parameters")
    private Map<String, Object> parameters;

    /** 提案发起人 */
    @JsonProperty("proposer")
    private String proposer;

    /** 执行结果（执行器回写，包含成功/失败信息、交易哈希、版本号等） */
    @JsonProperty("executionResult")
    private Map<String, Object> executionResult;

    /**
     * 初始化提案状态（包级方法，GOV-P1-01）。
     *
     * <p>仅允许在状态为 {@code null} 时设置初始状态（PENDING 或 ACTIVE）。
     * 同包的 {@link DefaultGovernanceService} 在 {@code createProposal} 时调用。
     *
     * @param initialState 初始状态（须为 PENDING 或 ACTIVE）
     * @throws IllegalStateException 如果状态已设置（非 null）
     * @throws IllegalArgumentException 如果 initialState 为 null 或非初始状态
     */
    void initState(ProposalState initialState) {
        if (this.state != null) {
            throw new IllegalStateException(
                    "Cannot init state: state already set to " + this.state);
        }
        if (initialState == null) {
            throw new IllegalArgumentException("initialState must not be null");
        }
        if (initialState != ProposalState.PENDING && initialState != ProposalState.ACTIVE) {
            throw new IllegalArgumentException(
                    "initialState must be PENDING or ACTIVE, got: " + initialState);
        }
        this.state = initialState;
    }

    /**
     * 执行合法状态转换（GOV-P1-01）。
     *
     * <p>校验当前状态到目标状态的转换是否合法，非法转换抛出
     * {@link IllegalStateException}。相同状态的幂等转换（{@code state == newState}）
     * 不抛异常。
     *
     * <p><b>调用方限制</b>：仅 {@link GovernanceService} 和
     * {@link org.nexus.oracle.governance.execution.GovernanceExecutionDispatcher}
     * 及其委托的执行器（{@link org.nexus.oracle.governance.execution.SoftwareUpgradeExecutor}、
     * {@link org.nexus.oracle.governance.execution.TreasurySpendExecutor}）应调用此方法。
     * 虽然方法为 {@code public}（因跨包访问需要），但状态转换矩阵本身提供了安全保障：
     * 任意调用方只能执行合法转换，无法跳过中间状态（如 PENDING → EXECUTED）。
     *
     * @param newState 目标状态
     * @throws IllegalArgumentException 如果 newState 为 null
     * @throws IllegalStateException 如果当前状态到 newState 的转换不合法
     */
    public void transitionTo(ProposalState newState) {
        if (newState == null) {
            throw new IllegalArgumentException("newState must not be null");
        }
        if (this.state == null) {
            // 从 null 状态初始化（如反序列化后首次设置）
            this.state = newState;
            return;
        }
        if (this.state == newState) {
            // 幂等：相同状态不抛异常
            return;
        }
        Set<ProposalState> allowed = LEGAL_TRANSITIONS.get(this.state);
        if (allowed == null || !allowed.contains(newState)) {
            throw new IllegalStateException(
                    "Illegal proposal state transition: " + this.state + " -> " + newState
                            + " (legal transitions: " + LEGAL_TRANSITIONS.get(this.state) + ")");
        }
        this.state = newState;
    }
}
