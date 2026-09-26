package org.nexus.gateway.security.threeds;

import org.nexus.gateway.model.PaymentOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;

/**
 * 模拟 3DS Server — 实现 ACS（Access Control Server）的核心行为。
 *
 * <p>NexusChain 作为支付网关而非发卡行，不具备部署真实 ACS 的条件。
 * 本类模拟 EMVCo 3DS 2.0 规范中的 ACS 决策逻辑：
 * <ol>
 *   <li>基于风险评估分数决定 Frictionless 或 Challenge 流程</li>
 *   <li>Frictionless: 直接返回 transStatus=Y（Authenticated）</li>
 *   <li>Challenge: 返回 transStatus=C（Challenge Required），生成 ACS Challenge URL</li>
 *   <li>Challenge 完成: 验证挑战结果，返回 Y 或 N</li>
 * </ol>
 * </p>
 *
 * <p>未来可平滑替换为真实 ACS 对接实现。</p>
 */
@Service
public class ThreeDsServer {

    private static final Logger log = LoggerFactory.getLogger(ThreeDsServer.class);

    /** 模拟 ACS Challenge URL 前缀。 */
    private static final String ACS_CHALLENGE_URL_PREFIX = "https://acs.nexuschain.example.com/challenge?token=";

    /** 模拟 ACS 基础 URL。 */
    private static final String DEFAULT_ACS_URL = "https://acs.nexuschain.example.com";

    private final RiskAssessor riskAssessor;
    private final SecureRandom secureRandom = new SecureRandom();

    public ThreeDsServer(RiskAssessor riskAssessor) {
        this.riskAssessor = riskAssessor;
    }

    /**
     * 发起 3DS 认证 — 模拟 ACS 的风险评估与认证决策。
     *
     * <p>决策逻辑：
     * <ul>
     *   <li>风险分数 < {@code frictionlessThresholdScore} → Frictionless 流程，返回 Y</li>
     *   <li>风险分数 >= {@code frictionlessThresholdScore} → Challenge 流程，返回 C</li>
     * </ul>
     * </p>
     *
     * @param paymentOrder 支付订单
     * @param deviceInfo   设备信息
     * @param config       3DS 配置
     * @return 3DS 认证结果
     */
    public ThreeDsAuthResult initiateAuth(PaymentOrder paymentOrder,
                                          RiskAssessor.DeviceInfo deviceInfo,
                                          ThreeDsConfig config) {
        log.info("3DS Server 发起认证: orderId={}, merchantId={}",
                paymentOrder.getId(), paymentOrder.getMerchantId());

        // 1. 风险评估
        RiskAssessor.RiskAssessmentResult riskResult =
                riskAssessor.assessRisk(paymentOrder, deviceInfo);
        int riskScore = riskResult.getScore();

        log.info("3DS 风险评估完成: orderId={}, riskScore={}, factors={}",
                paymentOrder.getId(), riskScore, riskResult.getFactors());

        // 2. 决策：Frictionless 或 Challenge
        int threshold = config.getFrictionlessThresholdScore();

        if (riskScore < threshold) {
            // Frictionless 流程 — 直接认证通过
            return buildFrictionlessResult(paymentOrder, riskScore);
        } else {
            // Challenge 流程 — 需要 Challenge 验证
            return buildChallengeResult(paymentOrder, riskScore, config);
        }
    }

    /**
     * 完成 Challenge 认证 — 模拟 ACS 验证挑战结果。
     *
     * <p>模拟验证逻辑：挑战结果为 6 位数字且非全零视为验证通过。
     * 实际生产中应由真实 ACS 验证 OTP/生物识别等挑战结果。</p>
     *
     * @param authRecord    认证记录
     * @param challengeResult 挑战结果（如 OTP 验证码）
     * @return 3DS 认证结果
     */
    public ThreeDsAuthResult completeChallenge(ThreeDsAuthRecord authRecord,
                                               String challengeResult) {
        log.info("3DS Server 完成 Challenge: authRecordId={}, orderId={}",
                authRecord.getId(), authRecord.getPaymentOrderId());

        if (validateChallengeResult(challengeResult)) {
            // Challenge 验证通过
            return ThreeDsAuthResult.builder()
                    .transStatus(TransStatus.Y)
                    .authStatus(AuthStatus.COMPLETED)
                    .eci("05")
                    .authenticationValue(generateAuthenticationValue())
                    .riskScore(authRecord.getRiskScore())
                    .frictionlessFlow(false)
                    .challengeFlow(true)
                    .completedAt(LocalDateTime.now())
                    .build();
        } else {
            // Challenge 验证失败
            return ThreeDsAuthResult.builder()
                    .transStatus(TransStatus.N)
                    .authStatus(AuthStatus.FAILED)
                    .riskScore(authRecord.getRiskScore())
                    .frictionlessFlow(false)
                    .challengeFlow(true)
                    .errorCode("3DS_CHALLENGE_INVALID")
                    .errorDetail("挑战结果验证失败")
                    .completedAt(LocalDateTime.now())
                    .build();
        }
    }

    /**
     * 构建 Frictionless 认证结果。
     */
    private ThreeDsAuthResult buildFrictionlessResult(PaymentOrder paymentOrder, int riskScore) {
        log.info("3DS Frictionless 认证通过: orderId={}, riskScore={}",
                paymentOrder.getId(), riskScore);

        return ThreeDsAuthResult.builder()
                .transStatus(TransStatus.Y)
                .authStatus(AuthStatus.COMPLETED)
                .eci("05")
                .authenticationValue(generateAuthenticationValue())
                .riskScore(riskScore)
                .frictionlessFlow(true)
                .challengeFlow(false)
                .acsTransId(generateTransId())
                .dsTransId(generateTransId())
                .completedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 构建 Challenge 认证结果。
     */
    private ThreeDsAuthResult buildChallengeResult(PaymentOrder paymentOrder,
                                                   int riskScore,
                                                   ThreeDsConfig config) {
        String challengeToken = generateChallengeToken();
        String acsChallengeUrl = buildAcsChallengeUrl(challengeToken, config);

        log.info("3DS Challenge 认证要求: orderId={}, riskScore={}, challengeUrl={}",
                paymentOrder.getId(), riskScore, acsChallengeUrl);

        return ThreeDsAuthResult.builder()
                .transStatus(TransStatus.C)
                .authStatus(AuthStatus.INITIATED)
                .riskScore(riskScore)
                .frictionlessFlow(false)
                .challengeFlow(true)
                .acsChallengeUrl(acsChallengeUrl)
                .acsTransId(generateTransId())
                .dsTransId(generateTransId())
                .build();
    }

    /**
     * 模拟验证挑战结果 — 6 位数字且非全零视为通过。
     */
    private boolean validateChallengeResult(String challengeResult) {
        if (challengeResult == null || challengeResult.length() != 6) {
            return false;
        }
        try {
            int code = Integer.parseInt(challengeResult);
            return code > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 生成模拟的 Authentication Value（CAVV/AAV）。
     */
    private String generateAuthenticationValue() {
        byte[] value = new byte[20];
        secureRandom.nextBytes(value);
        return Base64.getEncoder().encodeToString(value);
    }

    /**
     * 生成模拟的交易 ID。
     */
    private String generateTransId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 生成 Challenge Token。
     */
    private String generateChallengeToken() {
        return "challenge_" + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 构建 ACS Challenge URL。
     */
    private String buildAcsChallengeUrl(String challengeToken, ThreeDsConfig config) {
        String baseUrl = (config.getAcsUrl() != null && !config.getAcsUrl().isBlank())
                ? config.getAcsUrl()
                : DEFAULT_ACS_URL;
        return baseUrl + "/challenge?token=" + challengeToken;
    }

    // --- 3DS 认证结果数据结构 ---

    /**
     * 3DS 认证结果，由模拟 ACS 返回。
     */
    public static class ThreeDsAuthResult {
        private TransStatus transStatus;
        private AuthStatus authStatus;
        private String eci;
        private String authenticationValue;
        private Integer riskScore;
        private boolean frictionlessFlow;
        private boolean challengeFlow;
        private String acsChallengeUrl;
        private String acsTransId;
        private String dsTransId;
        private String errorCode;
        private String errorDetail;
        private LocalDateTime completedAt;

        public static Builder builder() {
            return new Builder();
        }

        // --- Getters ---

        public TransStatus getTransStatus() { return transStatus; }
        public AuthStatus getAuthStatus() { return authStatus; }
        public String getEci() { return eci; }
        public String getAuthenticationValue() { return authenticationValue; }
        public Integer getRiskScore() { return riskScore; }
        public boolean isFrictionlessFlow() { return frictionlessFlow; }
        public boolean isChallengeFlow() { return challengeFlow; }
        public String getAcsChallengeUrl() { return acsChallengeUrl; }
        public String getAcsTransId() { return acsTransId; }
        public String getDsTransId() { return dsTransId; }
        public String getErrorCode() { return errorCode; }
        public String getErrorDetail() { return errorDetail; }
        public LocalDateTime getCompletedAt() { return completedAt; }

        /**
         * ThreeDsAuthResult 构建器。
         */
        public static class Builder {
            private final ThreeDsAuthResult result = new ThreeDsAuthResult();

            public Builder transStatus(TransStatus transStatus) {
                result.transStatus = transStatus;
                return this;
            }

            public Builder authStatus(AuthStatus authStatus) {
                result.authStatus = authStatus;
                return this;
            }

            public Builder eci(String eci) {
                result.eci = eci;
                return this;
            }

            public Builder authenticationValue(String authenticationValue) {
                result.authenticationValue = authenticationValue;
                return this;
            }

            public Builder riskScore(Integer riskScore) {
                result.riskScore = riskScore;
                return this;
            }

            public Builder frictionlessFlow(boolean frictionlessFlow) {
                result.frictionlessFlow = frictionlessFlow;
                return this;
            }

            public Builder challengeFlow(boolean challengeFlow) {
                result.challengeFlow = challengeFlow;
                return this;
            }

            public Builder acsChallengeUrl(String acsChallengeUrl) {
                result.acsChallengeUrl = acsChallengeUrl;
                return this;
            }

            public Builder acsTransId(String acsTransId) {
                result.acsTransId = acsTransId;
                return this;
            }

            public Builder dsTransId(String dsTransId) {
                result.dsTransId = dsTransId;
                return this;
            }

            public Builder errorCode(String errorCode) {
                result.errorCode = errorCode;
                return this;
            }

            public Builder errorDetail(String errorDetail) {
                result.errorDetail = errorDetail;
                return this;
            }

            public Builder completedAt(LocalDateTime completedAt) {
                result.completedAt = completedAt;
                return this;
            }

            public ThreeDsAuthResult build() {
                return result;
            }
        }
    }
}