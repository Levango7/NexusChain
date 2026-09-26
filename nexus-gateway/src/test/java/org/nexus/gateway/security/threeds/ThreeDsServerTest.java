package org.nexus.gateway.security.threeds;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.model.PaymentOrder;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ThreeDsServer 单元测试 — 验证模拟 ACS 的认证决策逻辑。
 *
 * <p>测试覆盖：
 * <ul>
 *   <li>低风险时返回 Frictionless (transStatus=Y)</li>
 *   <li>高风险时返回 Challenge (transStatus=C)</li>
 *   <li>Challenge 验证通过返回 Y，验证失败返回 N</li>
 *   <li>ACS Challenge URL 构建</li>
 * </ul>
 * </p>
 */
@DisplayName("ThreeDsServer 模拟 ACS 测试")
class ThreeDsServerTest {

    private RiskAssessor riskAssessor;
    private ThreeDsServer threeDsServer;

    @BeforeEach
    void setUp() {
        riskAssessor = mock(RiskAssessor.class);
        threeDsServer = new ThreeDsServer(riskAssessor);
    }

    // --- 辅助方法 ---

    /**
     * 创建低风险评估结果（分数 < 阈值）。
     */
    private RiskAssessor.RiskAssessmentResult lowRiskResult() {
        return new RiskAssessor.RiskAssessmentResult(10, java.util.Collections.emptyList());
    }

    /**
     * 创建高风险评估结果（分数 >= 阈值）。
     */
    private RiskAssessor.RiskAssessmentResult highRiskResult() {
        return new RiskAssessor.RiskAssessmentResult(80, java.util.Collections.emptyList());
    }

    /**
     * 创建测试用 PaymentOrder。
     */
    private PaymentOrder testOrder() {
        PaymentOrder order = new PaymentOrder();
        order.setId(1L);
        order.setOrderNo("ORD-TEST-001");
        order.setMerchantId(200L);
        order.setTenantId("tenant-001");
        order.setAmount(BigDecimal.valueOf(500));
        return order;
    }

    /**
     * 创建测试用 DeviceInfo。
     */
    private RiskAssessor.DeviceInfo testDeviceInfo() {
        return new RiskAssessor.DeviceInfo(
                "192.168.1.1", "Mozilla/5.0", "zh-CN", "fp-123", true);
    }

    /**
     * 创建启用的 3DS 配置，阈值 60。
     */
    private ThreeDsConfig enabledConfig() {
        ThreeDsConfig config = new ThreeDsConfig();
        config.setId(1L);
        config.setTenantId("tenant-001");
        config.setMerchantId(200L);
        config.setEnabled(true);
        config.setFrictionlessThresholdScore(60);
        config.setChallengeTimeoutSeconds(300);
        config.setAcsUrl("https://acs.test.example.com");
        return config;
    }

    // --- initiateAuth 测试 ---

    @Nested
    @DisplayName("initiateAuth - 发起 3DS 认证")
    class InitiateAuth {

        @Test
        @DisplayName("低风险（分数 < 阈值）→ Frictionless 流程，transStatus=Y")
        void lowRisk_returnsFrictionlessY() {
            PaymentOrder order = testOrder();
            RiskAssessor.DeviceInfo device = testDeviceInfo();
            ThreeDsConfig config = enabledConfig();

            when(riskAssessor.assessRisk(order, device)).thenReturn(lowRiskResult());

            ThreeDsServer.ThreeDsAuthResult result =
                    threeDsServer.initiateAuth(order, device, config);

            assertEquals(TransStatus.Y, result.getTransStatus(),
                    "低风险应返回 transStatus=Y（Frictionless 认证通过）");
            assertEquals(AuthStatus.COMPLETED, result.getAuthStatus(),
                    "Frictionless 认证状态应为 COMPLETED");
            assertTrue(result.isFrictionlessFlow(),
                    "应为 Frictionless 流程");
            assertFalse(result.isChallengeFlow(),
                    "不应为 Challenge 流程");
            assertEquals("05", result.getEci(),
                    "Frictionless 认证 ECI 应为 05");
            assertNotNull(result.getAuthenticationValue(),
                    "应有 Authentication Value");
            assertNotNull(result.getAcsTransId(), "应有 ACS 交易 ID");
            assertNotNull(result.getDsTransId(), "应有 DS 交易 ID");
            assertNotNull(result.getCompletedAt(), "应有完成时间");
        }

        @Test
        @DisplayName("高风险（分数 >= 阈值）→ Challenge 流程，transStatus=C")
        void highRisk_returnsChallengeC() {
            PaymentOrder order = testOrder();
            RiskAssessor.DeviceInfo device = testDeviceInfo();
            ThreeDsConfig config = enabledConfig();

            when(riskAssessor.assessRisk(order, device)).thenReturn(highRiskResult());

            ThreeDsServer.ThreeDsAuthResult result =
                    threeDsServer.initiateAuth(order, device, config);

            assertEquals(TransStatus.C, result.getTransStatus(),
                    "高风险应返回 transStatus=C（需要 Challenge 验证）");
            assertEquals(AuthStatus.INITIATED, result.getAuthStatus(),
                    "Challenge 流程认证状态应为 INITIATED");
            assertFalse(result.isFrictionlessFlow(),
                    "不应为 Frictionless 流程");
            assertTrue(result.isChallengeFlow(),
                    "应为 Challenge 流程");
            assertNotNull(result.getAcsChallengeUrl(),
                    "Challenge 流程应有 ACS Challenge URL");
            assertNotNull(result.getAcsTransId(), "应有 ACS 交易 ID");
            assertNotNull(result.getDsTransId(), "应有 DS 交易 ID");
            assertNull(result.getEci(),
                    "Challenge 流程不应有 ECI（尚未认证完成）");
        }

        @Test
        @DisplayName("风险分数恰好等于阈值 → Challenge 流程（>= 阈值即 Challenge）")
        void scoreEqualsThreshold_returnsChallenge() {
            PaymentOrder order = testOrder();
            RiskAssessor.DeviceInfo device = testDeviceInfo();
            ThreeDsConfig config = enabledConfig(); // 阈值 60

            // 分数恰好等于阈值 60
            RiskAssessor.RiskAssessmentResult boundaryResult =
                    new RiskAssessor.RiskAssessmentResult(60, java.util.Collections.emptyList());
            when(riskAssessor.assessRisk(order, device)).thenReturn(boundaryResult);

            ThreeDsServer.ThreeDsAuthResult result =
                    threeDsServer.initiateAuth(order, device, config);

            assertEquals(TransStatus.C, result.getTransStatus(),
                    "分数等于阈值应返回 Challenge（>= 阈值即 Challenge）");
        }

        @Test
        @DisplayName("风险分数恰好低于阈值 1 分 → Frictionless 流程")
        void scoreOneBelowThreshold_returnsFrictionless() {
            PaymentOrder order = testOrder();
            RiskAssessor.DeviceInfo device = testDeviceInfo();
            ThreeDsConfig config = enabledConfig(); // 阈值 60

            // 分数 59，低于阈值 60
            RiskAssessor.RiskAssessmentResult boundaryResult =
                    new RiskAssessor.RiskAssessmentResult(59, java.util.Collections.emptyList());
            when(riskAssessor.assessRisk(order, device)).thenReturn(boundaryResult);

            ThreeDsServer.ThreeDsAuthResult result =
                    threeDsServer.initiateAuth(order, device, config);

            assertEquals(TransStatus.Y, result.getTransStatus(),
                    "分数低于阈值应返回 Frictionless");
        }

        @Test
        @DisplayName("Frictionless 结果中 riskScore 与评估结果一致")
        void frictionlessResult_riskScoreMatchesAssessment() {
            PaymentOrder order = testOrder();
            RiskAssessor.DeviceInfo device = testDeviceInfo();
            ThreeDsConfig config = enabledConfig();

            when(riskAssessor.assessRisk(order, device)).thenReturn(lowRiskResult()); // 分数 10

            ThreeDsServer.ThreeDsAuthResult result =
                    threeDsServer.initiateAuth(order, device, config);

            assertEquals(10, result.getRiskScore(),
                    "Frictionless 结果中 riskScore 应与评估分数一致");
        }

        @Test
        @DisplayName("Challenge 结果中 riskScore 与评估结果一致")
        void challengeResult_riskScoreMatchesAssessment() {
            PaymentOrder order = testOrder();
            RiskAssessor.DeviceInfo device = testDeviceInfo();
            ThreeDsConfig config = enabledConfig();

            when(riskAssessor.assessRisk(order, device)).thenReturn(highRiskResult()); // 分数 80

            ThreeDsServer.ThreeDsAuthResult result =
                    threeDsServer.initiateAuth(order, device, config);

            assertEquals(80, result.getRiskScore(),
                    "Challenge 结果中 riskScore 应与评估分数一致");
        }

        @Test
        @DisplayName("调用 riskAssessor.assessRisk 一次")
        void assessRiskCalledOnce() {
            PaymentOrder order = testOrder();
            RiskAssessor.DeviceInfo device = testDeviceInfo();
            ThreeDsConfig config = enabledConfig();

            when(riskAssessor.assessRisk(any(), any())).thenReturn(lowRiskResult());

            threeDsServer.initiateAuth(order, device, config);

            verify(riskAssessor, times(1)).assessRisk(order, device);
        }
    }

    // --- ACS Challenge URL 测试 ---

    @Nested
    @DisplayName("ACS Challenge URL 构建")
    class AcsChallengeUrl {

        @Test
        @DisplayName("配置中有 acsUrl → 使用配置的 ACS URL 构建 Challenge URL")
        void configHasAcsUrl_usesConfigUrl() {
            PaymentOrder order = testOrder();
            RiskAssessor.DeviceInfo device = testDeviceInfo();
            ThreeDsConfig config = enabledConfig();
            config.setAcsUrl("https://custom-acs.example.com");

            when(riskAssessor.assessRisk(order, device)).thenReturn(highRiskResult());

            ThreeDsServer.ThreeDsAuthResult result =
                    threeDsServer.initiateAuth(order, device, config);

            assertNotNull(result.getAcsChallengeUrl());
            assertTrue(result.getAcsChallengeUrl().startsWith("https://custom-acs.example.com/challenge?token="),
                    "Challenge URL 应使用配置中的 ACS URL");
        }

        @Test
        @DisplayName("配置中 acsUrl 为 null → 使用默认 ACS URL")
        void configAcsUrlNull_usesDefaultUrl() {
            PaymentOrder order = testOrder();
            RiskAssessor.DeviceInfo device = testDeviceInfo();
            ThreeDsConfig config = enabledConfig();
            config.setAcsUrl(null);

            when(riskAssessor.assessRisk(order, device)).thenReturn(highRiskResult());

            ThreeDsServer.ThreeDsAuthResult result =
                    threeDsServer.initiateAuth(order, device, config);

            assertNotNull(result.getAcsChallengeUrl());
            assertTrue(result.getAcsChallengeUrl().startsWith("https://acs.nexuschain.example.com/challenge?token="),
                    "acsUrl 为 null 时应使用默认 ACS URL");
        }

        @Test
        @DisplayName("配置中 acsUrl 为空白 → 使用默认 ACS URL")
        void configAcsUrlBlank_usesDefaultUrl() {
            PaymentOrder order = testOrder();
            RiskAssessor.DeviceInfo device = testDeviceInfo();
            ThreeDsConfig config = enabledConfig();
            config.setAcsUrl("   ");

            when(riskAssessor.assessRisk(order, device)).thenReturn(highRiskResult());

            ThreeDsServer.ThreeDsAuthResult result =
                    threeDsServer.initiateAuth(order, device, config);

            assertNotNull(result.getAcsChallengeUrl());
            assertTrue(result.getAcsChallengeUrl().startsWith("https://acs.nexuschain.example.com/challenge?token="),
                    "acsUrl 为空白时应使用默认 ACS URL");
        }
    }

    // --- completeChallenge 测试 ---

    @Nested
    @DisplayName("completeChallenge - 完成 Challenge 认证")
    class CompleteChallenge {

        /**
         * 创建测试用认证记录。
         */
        private ThreeDsAuthRecord testAuthRecord() {
            ThreeDsAuthRecord record = new ThreeDsAuthRecord();
            record.setId(1L);
            record.setTenantId("tenant-001");
            record.setMerchantId(200L);
            record.setPaymentOrderId(1L);
            record.setAuthStatus(AuthStatus.INITIATED);
            record.setTransStatus(TransStatus.C);
            record.setRiskScore(80);
            return record;
        }

        @Test
        @DisplayName("有效的 6 位非零挑战结果 → transStatus=Y，认证完成")
        void validChallengeResult_returnsY() {
            ThreeDsAuthRecord record = testAuthRecord();

            ThreeDsServer.ThreeDsAuthResult result =
                    threeDsServer.completeChallenge(record, "123456");

            assertEquals(TransStatus.Y, result.getTransStatus(),
                    "有效挑战结果应返回 transStatus=Y");
            assertEquals(AuthStatus.COMPLETED, result.getAuthStatus(),
                    "有效挑战结果认证状态应为 COMPLETED");
            assertTrue(result.isChallengeFlow(),
                    "应为 Challenge 流程");
            assertFalse(result.isFrictionlessFlow(),
                    "不应为 Frictionless 流程");
            assertEquals("05", result.getEci(),
                    "Challenge 验证通过 ECI 应为 05");
            assertNotNull(result.getAuthenticationValue(),
                    "应有 Authentication Value");
            assertNotNull(result.getCompletedAt(),
                    "应有完成时间");
        }

        @Test
        @DisplayName("无效的挑战结果（全零）→ transStatus=N，认证失败")
        void allZeroChallengeResult_returnsN() {
            ThreeDsAuthRecord record = testAuthRecord();

            ThreeDsServer.ThreeDsAuthResult result =
                    threeDsServer.completeChallenge(record, "000000");

            assertEquals(TransStatus.N, result.getTransStatus(),
                    "全零挑战结果应返回 transStatus=N");
            assertEquals(AuthStatus.FAILED, result.getAuthStatus(),
                    "全零挑战结果认证状态应为 FAILED");
            assertEquals("3DS_CHALLENGE_INVALID", result.getErrorCode(),
                    "应有错误码 3DS_CHALLENGE_INVALID");
            assertNotNull(result.getErrorDetail(),
                    "应有错误详情");
        }

        @Test
        @DisplayName("挑战结果长度不足 6 位 → transStatus=N")
        void shortChallengeResult_returnsN() {
            ThreeDsAuthRecord record = testAuthRecord();

            ThreeDsServer.ThreeDsAuthResult result =
                    threeDsServer.completeChallenge(record, "12345");

            assertEquals(TransStatus.N, result.getTransStatus(),
                    "长度不足 6 位应返回 transStatus=N");
            assertEquals(AuthStatus.FAILED, result.getAuthStatus());
        }

        @Test
        @DisplayName("挑战结果长度超过 6 位 → transStatus=N")
        void longChallengeResult_returnsN() {
            ThreeDsAuthRecord record = testAuthRecord();

            ThreeDsServer.ThreeDsAuthResult result =
                    threeDsServer.completeChallenge(record, "1234567");

            assertEquals(TransStatus.N, result.getTransStatus(),
                    "长度超过 6 位应返回 transStatus=N");
            assertEquals(AuthStatus.FAILED, result.getAuthStatus());
        }

        @Test
        @DisplayName("挑战结果为 null → transStatus=N")
        void nullChallengeResult_returnsN() {
            ThreeDsAuthRecord record = testAuthRecord();

            ThreeDsServer.ThreeDsAuthResult result =
                    threeDsServer.completeChallenge(record, null);

            assertEquals(TransStatus.N, result.getTransStatus(),
                    "null 挑战结果应返回 transStatus=N");
            assertEquals(AuthStatus.FAILED, result.getAuthStatus());
        }

        @Test
        @DisplayName("挑战结果包含非数字字符 → transStatus=N")
        void nonNumericChallengeResult_returnsN() {
            ThreeDsAuthRecord record = testAuthRecord();

            ThreeDsServer.ThreeDsAuthResult result =
                    threeDsServer.completeChallenge(record, "abcdef");

            assertEquals(TransStatus.N, result.getTransStatus(),
                    "非数字挑战结果应返回 transStatus=N");
            assertEquals(AuthStatus.FAILED, result.getAuthStatus());
        }

        @Test
        @DisplayName("Challenge 结果中 riskScore 与认证记录一致")
        void challengeResult_riskScoreMatchesRecord() {
            ThreeDsAuthRecord record = testAuthRecord();
            record.setRiskScore(75);

            ThreeDsServer.ThreeDsAuthResult result =
                    threeDsServer.completeChallenge(record, "123456");

            assertEquals(75, result.getRiskScore(),
                    "Challenge 结果中 riskScore 应与认证记录一致");
        }
    }
}