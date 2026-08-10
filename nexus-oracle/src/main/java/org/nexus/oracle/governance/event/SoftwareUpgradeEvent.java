package org.nexus.oracle.governance.event;

import org.nexus.oracle.governance.Proposal;

import java.time.Instant;
import java.util.Map;

/**
 * 软件升级治理事件。
 *
 * <p>当 {@code SOFTWARE_UPGRADE} 类型提案通过并触发执行时发布。
 * 监听方可据此事件驱动 Nacos 配置变更、滚动重启、灰度发布等下游流程。
 *
 * <p>事件载荷包含：
 * <ul>
 *   <li>{@code proposalId} — 关联提案 ID</li>
 *   <li>{@code target} — 升级目标服务（gateway / bridge / signing / wallet）</li>
 *   <li>{@code version} — 目标版本号（如 2.1.0）</li>
 *   <li>{@code config} — 升级附带配置（可选）</li>
 *   <li>{@code timestamp} — 事件发布时间</li>
 * </ul>
 *
 * @since 2.0.0
 */
public class SoftwareUpgradeEvent {

    /** 关联提案 ID */
    private final String proposalId;

    /** 升级目标服务 */
    private final String target;

    /** 目标版本号 */
    private final String version;

    /** 升级附带配置 */
    private final Map<String, Object> config;

    /** 事件发布时间 */
    private final Instant timestamp;

    /** 关联提案（可选，便于监听方获取完整上下文） */
    private final Proposal proposal;

    /**
     * 构造软件升级事件。
     *
     * @param proposalId 关联提案 ID
     * @param target     升级目标服务
     * @param version    目标版本号
     * @param config     升级附带配置（可为 {@code null}）
     * @param proposal   关联提案（可为 {@code null}）
     */
    public SoftwareUpgradeEvent(String proposalId, String target, String version,
                                Map<String, Object> config, Proposal proposal) {
        this.proposalId = proposalId;
        this.target = target;
        this.version = version;
        this.config = config;
        this.proposal = proposal;
        this.timestamp = Instant.now();
    }

    /** @return 关联提案 ID */
    public String getProposalId() {
        return proposalId;
    }

    /** @return 升级目标服务 */
    public String getTarget() {
        return target;
    }

    /** @return 目标版本号 */
    public String getVersion() {
        return version;
    }

    /** @return 升级附带配置 */
    public Map<String, Object> getConfig() {
        return config;
    }

    /** @return 事件发布时间 */
    public Instant getTimestamp() {
        return timestamp;
    }

    /** @return 关联提案 */
    public Proposal getProposal() {
        return proposal;
    }

    @Override
    public String toString() {
        return "SoftwareUpgradeEvent{proposalId='" + proposalId + "', target='" + target
                + "', version='" + version + "', timestamp=" + timestamp + '}';
    }
}