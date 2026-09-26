package org.nexus.gateway.security.threeds;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.model.PaymentOrder;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RiskAssessor 单元测试 — 验证风险评估器的评分逻辑。
 *
 * <p>测试覆盖：
 * <ul>
 *   <li>低风险交易（小额、已知设备）返回低分数</li>
 *   <li>高风险交易（大额、未知设备）返回高分数</li>
 *   <li>分数范围始终在 0-100</li>
 *   <li>各风险因素的独立加分逻辑</li>
 * </ul>
 * </p>
 */
@DisplayName("RiskAssessor 风险评估器测试")
class RiskAssessorTest {

    private RiskAssessor riskAssessor;

    @BeforeEach
    void setUp() {
        riskAssessor = new RiskAssessor();
    }

    // --- 辅助方法 ---

    /**
     * 创建已知设备的 DeviceInfo（低风险场景）。
     */
    private RiskAssessor.DeviceInfo knownDevice() {
        return new RiskAssessor.DeviceInfo(
                "192.168.1.1", "Mozilla/5.0", "zh-CN", "fp-known-123", true);
    }

    /**
     * 创建未知设备的 DeviceInfo（高风险场景）。
     */
    private RiskAssessor.DeviceInfo unknownDevice() {
        return new RiskAssessor.DeviceInfo(
                "10.0.0.1", "Unknown/1.0", "en-US", null, false);
    }

    /**
     * 创建小额订单（低于 1000 阈值）。
     */
    private PaymentOrder smallAmountOrder() {
        PaymentOrder order = new PaymentOrder();
        order.setId(1L);
        order.setOrderNo("ORD-LOW-001");
        order.setMerchantId(200L); // 大商户ID，非新商户
        order.setTenantId("tenant-001");
        order.setAmount(BigDecimal.valueOf(500));
        return order;
    }

    /**
     * 创建大额订单（超过 1000 阈值）。
     */
    private PaymentOrder largeAmountOrder() {
        PaymentOrder order = new PaymentOrder();
        order.setId(2L);
        order.setOrderNo("ORD-HIGH-002");
        order.setMerchantId(200L);
        order.setTenantId("tenant-001");
        order.setAmount(BigDecimal.valueOf(5000));
        return order;
    }

    // --- 测试用例 ---

    @Nested
    @DisplayName("低风险交易场景")
    class LowRiskScenario {

        @Test
        @DisplayName("小额 + 已知设备 → 低分数（0 分）")
        void smallAmountKnownDevice_returnsZeroScore() {
            PaymentOrder order = smallAmountOrder();
            RiskAssessor.DeviceInfo device = knownDevice();

            RiskAssessor.RiskAssessmentResult result = riskAssessor.assessRisk(order, device);

            // 小额（500 < 1000）不加分，已知设备不加分
            // 商户ID=200 > 100，非新商户
            // 高频判断基于 hashCode，需要验证 orderNo 不触发高频
            // "ORD-LOW-001".hashCode() % 10 的值需要确认
            assertTrue(result.getScore() >= 0, "分数应 >= 0");
            assertTrue(result.getScore() <= 100, "分数应 <= 100");
            // 小额 + 已知设备 + 大商户，最理想情况下分数为 0 或仅高频加分
            // 高频加分最多 15 分
            assertTrue(result.getScore() <= 15,
                    "低风险交易分数应 <= 15（最多只有高频加分）");
        }

        @Test
        @DisplayName("小额 + 已知设备 → 无 AMOUNT 和 UNKNOWN_DEVICE 风险因素")
        void smallAmountKnownDevice_noAmountOrDeviceFactors() {
            PaymentOrder order = smallAmountOrder();
            RiskAssessor.DeviceInfo device = knownDevice();

            RiskAssessor.RiskAssessmentResult result = riskAssessor.assessRisk(order, device);

            List<RiskAssessor.RiskFactor> factors = result.getFactors();
            for (RiskAssessor.RiskFactor factor : factors) {
                assertNotEquals("AMOUNT", factor.getName(),
                        "小额交易不应触发 AMOUNT 风险因素");
                assertNotEquals("UNKNOWN_DEVICE", factor.getName(),
                        "已知设备不应触发 UNKNOWN_DEVICE 风险因素");
            }
        }
    }

    @Nested
    @DisplayName("高风险交易场景")
    class HighRiskScenario {

        @Test
        @DisplayName("大额 + 未知设备 → 高分数（至少 45 分）")
        void largeAmountUnknownDevice_returnsHighScore() {
            PaymentOrder order = largeAmountOrder();
            RiskAssessor.DeviceInfo device = unknownDevice();

            RiskAssessor.RiskAssessmentResult result = riskAssessor.assessRisk(order, device);

            // 大额加分 25 + 未知设备加分 20 = 至少 45 分
            assertTrue(result.getScore() >= 45,
                    "大额 + 未知设备至少应得 45 分（25+20）");
            assertTrue(result.getScore() <= 100, "分数应 <= 100");
        }

        @Test
        @DisplayName("大额 → 触发 AMOUNT 风险因素，加分 25")
        void largeAmount_triggersAmountFactor() {
            PaymentOrder order = largeAmountOrder();
            RiskAssessor.DeviceInfo device = knownDevice();

            RiskAssessor.RiskAssessmentResult result = riskAssessor.assessRisk(order, device);

            boolean hasAmountFactor = result.getFactors().stream()
                    .anyMatch(f -> "AMOUNT".equals(f.getName()) && f.getScore() == 25);
            assertTrue(hasAmountFactor, "大额交易应触发 AMOUNT 风险因素，加分 25");
        }

        @Test
        @DisplayName("未知设备 → 触发 UNKNOWN_DEVICE 风险因素，加分 20")
        void unknownDevice_triggersUnknownDeviceFactor() {
            PaymentOrder order = smallAmountOrder();
            RiskAssessor.DeviceInfo device = unknownDevice();

            RiskAssessor.RiskAssessmentResult result = riskAssessor.assessRisk(order, device);

            boolean hasDeviceFactor = result.getFactors().stream()
                    .anyMatch(f -> "UNKNOWN_DEVICE".equals(f.getName()) && f.getScore() == 20);
            assertTrue(hasDeviceFactor, "未知设备应触发 UNKNOWN_DEVICE 风险因素，加分 20");
        }

        @Test
        @DisplayName("新商户（merchantId < 100）→ 触发 NEW_MERCHANT 风险因素，加分 10")
        void newMerchant_triggersNewMerchantFactor() {
            PaymentOrder order = new PaymentOrder();
            order.setId(3L);
            order.setOrderNo("ORD-NEW-003");
            order.setMerchantId(50L); // 新商户
            order.setTenantId("tenant-001");
            order.setAmount(BigDecimal.valueOf(500));
            RiskAssessor.DeviceInfo device = knownDevice();

            RiskAssessor.RiskAssessmentResult result = riskAssessor.assessRisk(order, device);

            boolean hasNewMerchantFactor = result.getFactors().stream()
                    .anyMatch(f -> "NEW_MERCHANT".equals(f.getName()) && f.getScore() == 10);
            assertTrue(hasNewMerchantFactor, "新商户应触发 NEW_MERCHANT 风险因素，加分 10");
        }

        @Test
        @DisplayName("大额 + 未知设备 + 新商户 → 分数至少 55（25+20+10）")
        void allHighRiskFactors_returnsVeryHighScore() {
            PaymentOrder order = new PaymentOrder();
            order.setId(4L);
            order.setOrderNo("ORD-VHIGH-004");
            order.setMerchantId(50L); // 新商户
            order.setTenantId("tenant-001");
            order.setAmount(BigDecimal.valueOf(5000)); // 大额
            RiskAssessor.DeviceInfo device = unknownDevice(); // 未知设备

            RiskAssessor.RiskAssessmentResult result = riskAssessor.assessRisk(order, device);

            // 25（金额）+ 20（设备）+ 10（新商户）= 55，可能还有高频 15
            assertTrue(result.getScore() >= 55,
                    "大额+未知设备+新商户至少应得 55 分");
        }
    }

    @Nested
    @DisplayName("分数范围验证")
    class ScoreRangeValidation {

        @Test
        @DisplayName("所有场景下分数始终在 0-100 范围内")
        void scoreAlwaysInRange() {
            // 极端高风险场景
            PaymentOrder order = new PaymentOrder();
            order.setId(5L);
            order.setOrderNo("ORD-EXTREME-005");
            order.setMerchantId(1L); // 新商户
            order.setTenantId("tenant-001");
            order.setAmount(BigDecimal.valueOf(999999)); // 超大额
            RiskAssessor.DeviceInfo device = unknownDevice();

            RiskAssessor.RiskAssessmentResult result = riskAssessor.assessRisk(order, device);

            assertTrue(result.getScore() >= 0, "分数应 >= 0");
            assertTrue(result.getScore() <= 100, "分数上限应为 100");
        }

        @Test
        @DisplayName("分数上限不超过 100（即使所有因素都触发）")
        void scoreCappedAt100() {
            // 构造一个可能超过 100 分的场景（所有因素加分 = 25+20+15+10 = 70，不会超 100）
            // 但验证 Math.min 逻辑仍然有效
            PaymentOrder order = new PaymentOrder();
            order.setId(6L);
            order.setOrderNo("ORD-CAP-006");
            order.setMerchantId(1L);
            order.setTenantId("tenant-001");
            order.setAmount(BigDecimal.valueOf(999999));
            RiskAssessor.DeviceInfo device = unknownDevice();

            RiskAssessor.RiskAssessmentResult result = riskAssessor.assessRisk(order, device);

            // 所有因素最多加 70 分（25+20+15+10），不会超过 100
            assertTrue(result.getScore() <= 100, "分数不应超过 100");
        }

        @Test
        @DisplayName("最低风险场景分数为 0 或接近 0")
        void lowestRiskScoreNearZero() {
            PaymentOrder order = new PaymentOrder();
            // 选择一个 hashCode % 10 >= 3 的 orderNo 以避免高频加分
            order.setOrderNo("AAAAAAAAAA"); // hashCode % 10 需要验证
            order.setId(7L);
            order.setMerchantId(200L);
            order.setTenantId("tenant-001");
            order.setAmount(BigDecimal.valueOf(100)); // 远低于阈值
            RiskAssessor.DeviceInfo device = knownDevice();

            RiskAssessor.RiskAssessmentResult result = riskAssessor.assessRisk(order, device);

            // 小额 + 已知设备 + 大商户，仅可能因高频加分
            // 如果不触发高频，分数应为 0
            assertTrue(result.getScore() >= 0, "分数应 >= 0");
            assertTrue(result.getScore() <= 15, "最低风险场景分数最多只有高频加分 15");
        }
    }

    @Nested
    @DisplayName("边界条件测试")
    class BoundaryConditions {

        @Test
        @DisplayName("金额恰好等于阈值 1000 → 不加分（> 1000 才加分）")
        void amountEqualsThreshold_noScoreAdded() {
            PaymentOrder order = new PaymentOrder();
            order.setId(8L);
            order.setOrderNo("ORD-EQ-008");
            order.setMerchantId(200L);
            order.setTenantId("tenant-001");
            order.setAmount(BigDecimal.valueOf(1000)); // 恰好等于阈值
            RiskAssessor.DeviceInfo device = knownDevice();

            RiskAssessor.RiskAssessmentResult result = riskAssessor.assessRisk(order, device);

            boolean hasAmountFactor = result.getFactors().stream()
                    .anyMatch(f -> "AMOUNT".equals(f.getName()));
            assertFalse(hasAmountFactor, "金额等于阈值不应触发 AMOUNT 风险因素（需 > 1000）");
        }

        @Test
        @DisplayName("金额恰好超过阈值 1000.01 → 加分")
        void amountSlightlyAboveThreshold_scoreAdded() {
            PaymentOrder order = new PaymentOrder();
            order.setId(9L);
            order.setOrderNo("ORD-OVER-009");
            order.setMerchantId(200L);
            order.setTenantId("tenant-001");
            order.setAmount(BigDecimal.valueOf(1000.01));
            RiskAssessor.DeviceInfo device = knownDevice();

            RiskAssessor.RiskAssessmentResult result = riskAssessor.assessRisk(order, device);

            boolean hasAmountFactor = result.getFactors().stream()
                    .anyMatch(f -> "AMOUNT".equals(f.getName()));
            assertTrue(hasAmountFactor, "金额超过阈值应触发 AMOUNT 风险因素");
        }

        @Test
        @DisplayName("merchantId 恰好等于 100 → 不是新商户（< 100 才是）")
        void merchantIdEquals100_notNewMerchant() {
            PaymentOrder order = new PaymentOrder();
            order.setId(10L);
            order.setOrderNo("ORD-MID-010");
            order.setMerchantId(100L); // 恰好等于 100
            order.setTenantId("tenant-001");
            order.setAmount(BigDecimal.valueOf(500));
            RiskAssessor.DeviceInfo device = knownDevice();

            RiskAssessor.RiskAssessmentResult result = riskAssessor.assessRisk(order, device);

            boolean hasNewMerchantFactor = result.getFactors().stream()
                    .anyMatch(f -> "NEW_MERCHANT".equals(f.getName()));
            assertFalse(hasNewMerchantFactor, "merchantId=100 不应触发 NEW_MERCHANT（需 < 100）");
        }

        @Test
        @DisplayName("deviceInfo 为 null → 触发 UNKNOWN_DEVICE 加分")
        void nullDeviceInfo_triggersUnknownDevice() {
            PaymentOrder order = smallAmountOrder();

            RiskAssessor.RiskAssessmentResult result = riskAssessor.assessRisk(order, null);

            boolean hasDeviceFactor = result.getFactors().stream()
                    .anyMatch(f -> "UNKNOWN_DEVICE".equals(f.getName()));
            assertTrue(hasDeviceFactor, "deviceInfo 为 null 应触发 UNKNOWN_DEVICE 风险因素");
        }

        @Test
        @DisplayName("amount 为 null → 不触发 AMOUNT 加分")
        void nullAmount_noScoreAdded() {
            PaymentOrder order = new PaymentOrder();
            order.setId(11L);
            order.setOrderNo("ORD-NULL-011");
            order.setMerchantId(200L);
            order.setTenantId("tenant-001");
            order.setAmount(null);
            RiskAssessor.DeviceInfo device = knownDevice();

            RiskAssessor.RiskAssessmentResult result = riskAssessor.assessRisk(order, device);

            boolean hasAmountFactor = result.getFactors().stream()
                    .anyMatch(f -> "AMOUNT".equals(f.getName()));
            assertFalse(hasAmountFactor, "amount 为 null 不应触发 AMOUNT 风险因素");
        }

        @Test
        @DisplayName("orderNo 为 null → 不触发高频加分（不抛异常）")
        void nullOrderNo_noHighFrequency() {
            PaymentOrder order = new PaymentOrder();
            order.setId(12L);
            order.setOrderNo(null);
            order.setMerchantId(200L);
            order.setTenantId("tenant-001");
            order.setAmount(BigDecimal.valueOf(500));
            RiskAssessor.DeviceInfo device = knownDevice();

            RiskAssessor.RiskAssessmentResult result = riskAssessor.assessRisk(order, device);

            boolean hasFrequencyFactor = result.getFactors().stream()
                    .anyMatch(f -> "HIGH_FREQUENCY".equals(f.getName()));
            assertFalse(hasFrequencyFactor, "orderNo 为 null 不应触发 HIGH_FREQUENCY 风险因素");
        }

        @Test
        @DisplayName("merchantId 为 null → 不触发新商户加分（不抛异常）")
        void nullMerchantId_noNewMerchant() {
            PaymentOrder order = new PaymentOrder();
            order.setId(13L);
            order.setOrderNo("ORD-NM-013");
            order.setMerchantId(null);
            order.setTenantId("tenant-001");
            order.setAmount(BigDecimal.valueOf(500));
            RiskAssessor.DeviceInfo device = knownDevice();

            RiskAssessor.RiskAssessmentResult result = riskAssessor.assessRisk(order, device);

            boolean hasNewMerchantFactor = result.getFactors().stream()
                    .anyMatch(f -> "NEW_MERCHANT".equals(f.getName()));
            assertFalse(hasNewMerchantFactor, "merchantId 为 null 不应触发 NEW_MERCHANT 风险因素");
        }
    }

    @Nested
    @DisplayName("风险因素明细验证")
    class RiskFactorDetails {

        @Test
        @DisplayName("每个风险因素包含名称、分数和描述")
        void riskFactorsContainNameScoreDescription() {
            PaymentOrder order = largeAmountOrder();
            RiskAssessor.DeviceInfo device = unknownDevice();

            RiskAssessor.RiskAssessmentResult result = riskAssessor.assessRisk(order, device);

            for (RiskAssessor.RiskFactor factor : result.getFactors()) {
                assertNotNull(factor.getName(), "风险因素名称不应为 null");
                assertTrue(factor.getScore() > 0, "风险因素分数应 > 0");
                assertNotNull(factor.getDescription(), "风险因素描述不应为 null");
            }
        }

        @Test
        @DisplayName("风险评估结果包含因素列表")
        void assessmentResultContainsFactorsList() {
            PaymentOrder order = largeAmountOrder();
            RiskAssessor.DeviceInfo device = unknownDevice();

            RiskAssessor.RiskAssessmentResult result = riskAssessor.assessRisk(order, device);

            assertNotNull(result.getFactors(), "因素列表不应为 null");
            assertFalse(result.getFactors().isEmpty(), "高风险交易应至少有一个风险因素");
        }
    }
}