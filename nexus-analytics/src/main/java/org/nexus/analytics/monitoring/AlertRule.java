package org.nexus.analytics.monitoring;

import java.util.Map;
import java.util.Optional;

/**
 * 告警规则。
 *
 * <p>对单个指标求值，若命中阈值则返回应产生的告警；否则返回 {@link Optional#empty()}。
 *
 * <p>规则由 {@link ChainMonitorService} 在采集到指标后统一驱动求值。
 */
public interface AlertRule {

    /**
     * 规则名（如 "node.sync.lag"）。
     *
     * @return 规则名
     */
    String name();

    /**
     * 对给定指标求值。
     *
     * @param metric 指标键值对（如 {"syncLag": 120, "peerCount": 8}）
     * @return 命中规则时返回告警；否则空
     */
    Optional<Alert> evaluate(Map<String, Object> metric);
}