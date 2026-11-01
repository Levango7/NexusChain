package org.nexus.consensus.finality;

/**
 * 最终性投票拒绝计数输出端口（PLAN-066）。
 *
 * <p><b>为什么需要它</b>：PLAN-065 把单票 Ed25519 验签前移到计票/落库之前之后，
 * 三条拒绝路径（公钥绑定失败 / 验签失败 / 启动恢复丢弃坏行）都只打 WARN 日志。
 * 日志是<strong>本地、不可聚合、不可告警</strong>的：伪造票攻击是否发生、
 * 是否正在被攻击、是哪一个验证人地址被冒充，都无法在不动容器的情况下回答。
 * 本端口把"拒绝了什么、为什么拒绝、在哪条路径拒绝"变成可被
 * {@code /actuator/prometheus} 抓取、被告警规则消费的计数。</p>
 *
 * <p><b>为什么放在 consensus 包而不是 metrics 包</b>：{@code org.nexus.metrics}
 * 已依赖 {@code org.nexus.consensus.finality}（{@code FinalityMetrics} 消费
 * {@code FinalityCoordinator}）。若让 {@code FinalityGadget} 直接引用 metrics 包
 * 的具体类，会在两个包之间形成循环依赖。这里由被使用方（判定拒绝的那一侧）
 * 定义端口，{@code org.nexus.metrics} 做适配器实现，依赖方向保持单向。</p>
 *
 * <p><b>为什么拒绝原因是 enum 而不是自由字符串</b>：标签值直接决定 Prometheus
 * 序列基数。自由字符串意味着任一处 {@code log.warn} 的顺手拼接都能造出新标签，
 * 基数不可控；闭合词表由编译器保证，新增原因必须改这里（并同步告警与文档）。</p>
 *
 * <p>实现：{@link org.nexus.metrics.FinalityRejectionMetrics}（Micrometer
 * Counter，生产注入 {@code MeterRegistry}）。未注入时 {@code FinalityGadget}
 * 退化为"只打日志"——与原 PLAN-065 行为一致，不影响共识正确性。</p>
 */
public interface FinalityRejectionCounter {

    /**
     * 投票级拒绝原因（闭合词表）。
     *
     * <p>枚举名即指标标签值（{@code name().toLowerCase(Locale.ROOT)}），
     * 所以入参命名就是对外契约：改名即改指标标签，必须同步
     * {@code deploy/k8s/50-prometheus-rules.yaml} 与
     * {@code docs/plan/PLAN-066-finality-vote-rejection-metrics.md}。</p>
     */
    enum Reason {
        /** 投票者地址不在 ValidatorRegistry 中。 */
        VALIDATOR_UNKNOWN,
        /** 投票者在册但状态不是 ACTIVE（退出中/已罚没）。 */
        VALIDATOR_INACTIVE,
        /** 票本身畸形：null、地址为空等防御性拒绝。 */
        MALFORMED_VOTE,
        /** 票内公钥与注册公钥不一致（伪造身份）。 */
        PUBKEY_MISMATCH,
        /** 单票 Ed25519 验签失败（公钥正确但签名伪造——PLAN-065 的主缺口）。 */
        SIGNATURE_INVALID,
        /** epoch 总质押权重为 0（启动早期/注册表异常），无法做阈值判定。 */
        ZERO_TOTAL_WEIGHT
    }

    /**
     * 拒绝发生在哪条路径。
     *
     * <p>同一个原因在不同路径的含义不同：{@code SIGNATURE_INVALID@CONVERGENCE}
     * 表示<strong>库里躺着坏行</strong>（此前被写入或由对端写入），而
     * {@code SIGNATURE_INVALID@ADMISSION} 表示<strong>正在被网络攻击</strong>。</p>
     */
    enum Phase {
        /** {@link FinalityGadget#submitVote}：本地投票与 P2P 对端投票到达。 */
        ADMISSION,
        /** {@link FinalityGadget#convergeFinalityView}：把库里的票拉进本地视图。 */
        CONVERGENCE,
        /** {@link FinalityGadget#restoreFromStore()}：启动恢复时丢弃不可信行。 */
        RESTORE
    }

    /** 检查点级拒绝原因（闭合词表）。 */
    enum FinalizeReason {
        /** 已达阈值但聚合验签失败 ⇒ 该检查点不最终化，且进入 rejected 集。 */
        AGGREGATE_VERIFICATION_FAILED
    }

    /** 记一次投票级拒绝。 */
    void countVoteRejected(Reason reason, Phase phase);

    /** 记一次检查点级拒绝（阈值已过但最终化被拒）。 */
    void countFinalizeRejected(FinalizeReason reason);
}
