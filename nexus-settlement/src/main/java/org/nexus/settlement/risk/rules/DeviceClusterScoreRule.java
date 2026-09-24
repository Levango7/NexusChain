package org.nexus.settlement.risk.rules;

import org.nexus.settlement.risk.RiskScoringRule;
import org.nexus.settlement.risk.RiskTransaction;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 设备聚集检测评分规则。
 * <p>
 * 检测设备聚集风险：
 * <ul>
 *   <li>多账号同一设备（linkedAccountCount ≥ 5） → 90</li>
 *   <li>多账号同一设备（linkedAccountCount ≥ 3） → 60</li>
 *   <li>设备指纹异常（linkedDeviceCount ≥ 5） → 80</li>
 *   <li>设备指纹异常（linkedDeviceCount ≥ 3） → 50</li>
 *   <li>正常 → 0</li>
 * </ul>
 * 取所有维度中最高评分作为最终评分。
 * </p>
 *
 * <p>权重：8。check() 在 score ≥ 80 时返回 true（拦截）。</p>
 */
@Component
public class DeviceClusterScoreRule implements RiskScoringRule {

    private static final String RULE_ID = "DEVICE_CLUSTER_SCORE";

    /** 多账号同一设备高风险阈值 */
    private static final int LINKED_ACCOUNT_HIGH = 5;

    /** 多账号同一设备中风险阈值 */
    private static final int LINKED_ACCOUNT_MEDIUM = 3;

    /** 设备指纹异常高风险阈值 */
    private static final int LINKED_DEVICE_HIGH = 5;

    /** 设备指纹异常中风险阈值 */
    private static final int LINKED_DEVICE_MEDIUM = 3;

    @Override
    public String getRuleId() {
        return RULE_ID;
    }

    @Override
    public int getWeight() {
        return 8;
    }

    @Override
    public String getRuleDescription() {
        return "Device cluster scoring rule: evaluates risk by multi-account same device and device fingerprint anomaly";
    }

    @Override
    public int score(Object transaction) {
        if (Objects.isNull(transaction)) {
            return 0;
        }
        if (!(transaction instanceof RiskTransaction riskTx)) {
            return 0;
        }

        int maxScore = 0;

        // 多账号同一设备检测
        Integer linkedAccountCount = riskTx.getLinkedAccountCount();
        if (linkedAccountCount != null) {
            if (linkedAccountCount >= LINKED_ACCOUNT_HIGH) {
                maxScore = Math.max(maxScore, 90);
            } else if (linkedAccountCount >= LINKED_ACCOUNT_MEDIUM) {
                maxScore = Math.max(maxScore, 60);
            }
        }

        // 设备指纹异常检测
        Integer linkedDeviceCount = riskTx.getLinkedDeviceCount();
        if (linkedDeviceCount != null) {
            if (linkedDeviceCount >= LINKED_DEVICE_HIGH) {
                maxScore = Math.max(maxScore, 80);
            } else if (linkedDeviceCount >= LINKED_DEVICE_MEDIUM) {
                maxScore = Math.max(maxScore, 50);
            }
        }

        return maxScore;
    }

    @Override
    public boolean check(Object transaction) {
        return score(transaction) >= 80;
    }
}