package org.nexus.gateway.security.threeds;

import org.nexus.gateway.model.PaymentOrder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 3DS 风险评估器 — 基于交易上下文和设备信息输出风险评分。
 *
 * <p>评估因素及权重：
 * <ul>
 *   <li>交易金额：>1000 加分（金额越高风险越大）</li>
 *   <li>设备指纹：未知设备加分</li>
 *   <li>交易频率：高频交易加分</li>
 *   <li>商户历史：新商户加分</li>
 * </ul>
 * </p>
 *
 * <p>评分范围 0-100，分数越低表示风险越低，越可能走 Frictionless 流程。</p>
 */
@Service
public class RiskAssessor {

    /** 金额阈值：超过此值加分。 */
    private static final BigDecimal AMOUNT_THRESHOLD = BigDecimal.valueOf(1000);

    /** 金额风险加分。 */
    private static final int AMOUNT_RISK_SCORE = 25;

    /** 未知设备指纹加分。 */
    private static final int UNKNOWN_DEVICE_SCORE = 20;

    /** 高频交易加分。 */
    private static final int HIGH_FREQUENCY_SCORE = 15;

    /** 新商户加分。 */
    private static final int NEW_MERCHANT_SCORE = 10;

    /** 最大风险分数上限。 */
    private static final int MAX_SCORE = 100;

    /**
     * 评估交易风险，输出 0-100 分数及各因素明细。
     *
     * @param paymentOrder 支付订单
     * @param deviceInfo   设备信息
     * @return 风险评估结果
     */
    public RiskAssessmentResult assessRisk(PaymentOrder paymentOrder, DeviceInfo deviceInfo) {
        int score = 0;
        List<RiskFactor> factors = new ArrayList<>();

        // 因素1: 交易金额
        if (paymentOrder.getAmount() != null
                && paymentOrder.getAmount().compareTo(AMOUNT_THRESHOLD) > 0) {
            score += AMOUNT_RISK_SCORE;
            factors.add(new RiskFactor("AMOUNT", AMOUNT_RISK_SCORE,
                    "交易金额超过阈值 " + AMOUNT_THRESHOLD));
        }

        // 因素2: 设备指纹
        if (deviceInfo == null || !deviceInfo.isDeviceFingerprintKnown()) {
            score += UNKNOWN_DEVICE_SCORE;
            factors.add(new RiskFactor("UNKNOWN_DEVICE", UNKNOWN_DEVICE_SCORE,
                    "设备指纹未知或缺失"));
        }

        // 因素3: 交易频率（模拟判断 — 短时间内多笔交易视为高频）
        if (isHighFrequency(paymentOrder)) {
            score += HIGH_FREQUENCY_SCORE;
            factors.add(new RiskFactor("HIGH_FREQUENCY", HIGH_FREQUENCY_SCORE,
                    "交易频率异常"));
        }

        // 因素4: 商户历史（模拟判断 — 商户ID较小视为新商户）
        if (isNewMerchant(paymentOrder)) {
            score += NEW_MERCHANT_SCORE;
            factors.add(new RiskFactor("NEW_MERCHANT", NEW_MERCHANT_SCORE,
                    "新商户，历史交易数据不足"));
        }

        // 限制分数上限
        score = Math.min(score, MAX_SCORE);

        return new RiskAssessmentResult(score, factors);
    }

    /**
     * 模拟高频交易判断：基于订单号哈希的伪随机判断。
     * 实际生产中应查询近期交易记录统计频率。
     */
    private boolean isHighFrequency(PaymentOrder paymentOrder) {
        if (paymentOrder.getOrderNo() == null) {
            return false;
        }
        // 模拟：订单号哈希值模 10 < 3 视为高频（约 30% 概率）
        // 使用位掩码清除符号位，避免 Math.abs(Integer.MIN_VALUE) 溢出问题
        int hash = paymentOrder.getOrderNo().hashCode() & 0x7FFFFFFF;
        return hash % 10 < 3;
    }

    /**
     * 模拟新商户判断：商户 ID 小于 100 视为新商户。
     * 实际生产中应查询商户注册时间和历史交易量。
     */
    private boolean isNewMerchant(PaymentOrder paymentOrder) {
        return paymentOrder.getMerchantId() != null && paymentOrder.getMerchantId() < 100;
    }

    // --- 内部数据结构 ---

    /**
     * 设备信息载体，用于风险评估输入。
     */
    public static class DeviceInfo {
        private String ip;
        private String userAgent;
        private String acceptLanguage;
        private String deviceFingerprint;
        private boolean deviceFingerprintKnown;

        public DeviceInfo() {
        }

        public DeviceInfo(String ip, String userAgent, String acceptLanguage,
                          String deviceFingerprint, boolean deviceFingerprintKnown) {
            this.ip = ip;
            this.userAgent = userAgent;
            this.acceptLanguage = acceptLanguage;
            this.deviceFingerprint = deviceFingerprint;
            this.deviceFingerprintKnown = deviceFingerprintKnown;
        }

        public String getIp() { return ip; }
        public void setIp(String ip) { this.ip = ip; }

        public String getUserAgent() { return userAgent; }
        public void setUserAgent(String userAgent) { this.userAgent = userAgent; }

        public String getAcceptLanguage() { return acceptLanguage; }
        public void setAcceptLanguage(String acceptLanguage) { this.acceptLanguage = acceptLanguage; }

        public String getDeviceFingerprint() { return deviceFingerprint; }
        public void setDeviceFingerprint(String deviceFingerprint) {
            this.deviceFingerprint = deviceFingerprint;
        }

        public boolean isDeviceFingerprintKnown() { return deviceFingerprintKnown; }
        public void setDeviceFingerprintKnown(boolean deviceFingerprintKnown) {
            this.deviceFingerprintKnown = deviceFingerprintKnown;
        }
    }

    /**
     * 风险评估结果，包含总分和各风险因素明细。
     */
    public static class RiskAssessmentResult {
        private final int score;
        private final List<RiskFactor> factors;

        public RiskAssessmentResult(int score, List<RiskFactor> factors) {
            this.score = score;
            this.factors = factors;
        }

        public int getScore() { return score; }
        public List<RiskFactor> getFactors() { return factors; }
    }

    /**
     * 单个风险因素，包含因素名称、加分值和描述。
     */
    public static class RiskFactor {
        private final String name;
        private final int score;
        private final String description;

        public RiskFactor(String name, int score, String description) {
            this.name = name;
            this.score = score;
            this.description = description;
        }

        public String getName() { return name; }
        public int getScore() { return score; }
        public String getDescription() { return description; }
    }
}