package org.nexus.gateway.security.threeds;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.model.PaymentOrder;
import org.nexus.gateway.repository.PaymentOrderRepository;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ThreeDsService 单元测试 — 验证 3DS 认证服务的编排逻辑。
 *
 * <p>测试覆盖：
 * <ul>
 *   <li>initiateAuth 创建认证记录、调用 ACS、发布事件</li>
 *   <li>3DS 未启用时返回跳过结果（transStatus=U）</li>
 *   <li>completeAuth 完成挑战认证</li>
 *   <li>getAuthStatus 查询认证状态</li>
 *   <li>订单不存在时抛异常</li>
 * </ul>
 * </p>
 */
@DisplayName("ThreeDsService 3DS 认证服务测试")
class ThreeDsServiceTest {

    private ThreeDsConfigService configService;
    private ThreeDsServer threeDsServer;
    private ThreeDsAuthRecordRepository authRecordRepository;
    private PaymentOrderRepository paymentOrderRepository;
    private ApplicationEventPublisher eventPublisher;
    private ThreeDsService threeDsService;

    @BeforeEach
    void setUp() {
        configService = mock(ThreeDsConfigService.class);
        threeDsServer = mock(ThreeDsServer.class);
        authRecordRepository = mock(ThreeDsAuthRecordRepository.class);
        paymentOrderRepository = mock(PaymentOrderRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        threeDsService = new ThreeDsService(
                configService, threeDsServer, authRecordRepository,
                paymentOrderRepository, eventPublisher);
    }

    // --- 辅助方法 ---

    private PaymentOrder testOrder() {
        PaymentOrder order = new PaymentOrder();
        order.setId(1L);
        order.setOrderNo("ORD-TEST-001");
        order.setMerchantId(200L);
        order.setTenantId("tenant-001");
        order.setAmount(BigDecimal.valueOf(500));
        return order;
    }

    private ThreeDsConfig enabledConfig() {
        ThreeDsConfig config = new ThreeDsConfig();
        config.setId(1L);
        config.setTenantId("tenant-001");
        config.setMerchantId(200L);
        config.setEnabled(true);
        config.setFrictionlessThresholdScore(60);
        config.setChallengeTimeoutSeconds(300);
        return config;
    }

    private ThreeDsConfig disabledConfig() {
        ThreeDsConfig config = new ThreeDsConfig();
        config.setId(2L);
        config.setTenantId("tenant-001");
        config.setMerchantId(200L);
        config.setEnabled(false);
        config.setFrictionlessThresholdScore(60);
        config.setChallengeTimeoutSeconds(300);
        return config;
    }

    private RiskAssessor.DeviceInfo testDeviceInfo() {
        return new RiskAssessor.DeviceInfo(
                "192.168.1.1", "Mozilla/5.0", "zh-CN", "fp-123", true);
    }

    private ThreeDsServer.ThreeDsAuthResult frictionlessResult() {
        return ThreeDsServer.ThreeDsAuthResult.builder()
                .transStatus(TransStatus.Y)
                .authStatus(AuthStatus.COMPLETED)
                .eci("05")
                .authenticationValue("cavv-value")
                .riskScore(10)
                .frictionlessFlow(true)
                .challengeFlow(false)
                .acsTransId("acs-trans-id")
                .dsTransId("ds-trans-id")
                .completedAt(LocalDateTime.now())
                .build();
    }

    private ThreeDsServer.ThreeDsAuthResult challengeResult() {
        return ThreeDsServer.ThreeDsAuthResult.builder()
                .transStatus(TransStatus.C)
                .authStatus(AuthStatus.INITIATED)
                .riskScore(80)
                .frictionlessFlow(false)
                .challengeFlow(true)
                .acsChallengeUrl("https://acs.example.com/challenge?token=xxx")
                .acsTransId("acs-trans-id")
                .dsTransId("ds-trans-id")
                .build();
    }

    private ThreeDsServer.ThreeDsAuthResult challengeCompletedResult() {
        return ThreeDsServer.ThreeDsAuthResult.builder()
                .transStatus(TransStatus.Y)
                .authStatus(AuthStatus.COMPLETED)
                .eci("05")
                .authenticationValue("cavv-value")
                .riskScore(80)
                .frictionlessFlow(false)
                .challengeFlow(true)
                .completedAt(LocalDateTime.now())
                .build();
    }

    // --- initiateAuth 测试 ---

    @Nested
    @DisplayName("initiateAuth - 发起 3DS 认证")
    class InitiateAuth {

        @Test
        @DisplayName("3DS 未启用 → 返回跳过结果 transStatus=U")
        void threeDsDisabled_returnsSkipResult() {
            PaymentOrder order = testOrder();
            RiskAssessor.DeviceInfo device = testDeviceInfo();

            when(paymentOrderRepository.findById(1L)).thenReturn(Optional.of(order));
            when(configService.getConfig("tenant-001", 200L)).thenReturn(disabledConfig());

            ThreeDsServer.ThreeDsAuthResult result =
                    threeDsService.initiateAuth(1L, device);

            assertEquals(TransStatus.U, result.getTransStatus(),
                    "3DS 未启用应返回 transStatus=U");
            assertEquals(AuthStatus.COMPLETED, result.getAuthStatus(),
                    "跳过认证状态应为 COMPLETED");
            assertFalse(result.isFrictionlessFlow(), "不应为 Frictionless 流程");
            assertFalse(result.isChallengeFlow(), "不应为 Challenge 流程");
            assertNull(result.getRiskScore(), "跳过认证不应有风险分数");
            // 不应创建认证记录
            verify(authRecordRepository, never()).save(any());
            // 不应调用 ACS
            verify(threeDsServer, never()).initiateAuth(any(), any(), any());
            // 不应发布事件
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("3DS 启用 + Frictionless → 创建记录、调用 ACS、发布事件")
        void threeDsEnabled_frictionless_createsRecordAndPublishesEvent() {
            PaymentOrder order = testOrder();
            RiskAssessor.DeviceInfo device = testDeviceInfo();
            ThreeDsConfig config = enabledConfig();

            when(paymentOrderRepository.findById(1L)).thenReturn(Optional.of(order));
            when(configService.getConfig("tenant-001", 200L)).thenReturn(config);
            when(authRecordRepository.save(any(ThreeDsAuthRecord.class)))
                    .thenAnswer(invocation -> {
                        ThreeDsAuthRecord record = invocation.getArgument(0);
                        record.setId(100L);
                        return record;
                    });
            when(threeDsServer.initiateAuth(order, device, config)).thenReturn(frictionlessResult());

            ThreeDsServer.ThreeDsAuthResult result =
                    threeDsService.initiateAuth(1L, device);

            assertEquals(TransStatus.Y, result.getTransStatus(),
                    "Frictionless 应返回 transStatus=Y");
            assertEquals(AuthStatus.COMPLETED, result.getAuthStatus());

            // 验证认证记录被保存（创建 + 更新至少 2 次）
            verify(authRecordRepository, atLeast(2)).save(any(ThreeDsAuthRecord.class));

            // 验证更新认证记录（最终状态 COMPLETED + transStatus=Y）
            verify(authRecordRepository, atLeastOnce()).save(argThat(record ->
                    record.getAuthStatus() == AuthStatus.COMPLETED &&
                    record.getTransStatus() == TransStatus.Y &&
                    record.getPaymentOrderId().equals(1L)));

            // 验证调用 ACS
            verify(threeDsServer, times(1)).initiateAuth(order, device, config);

            // 验证发布事件（Frictionless 完成时）
            verify(eventPublisher, times(1)).publishEvent(any(ThreeDsAuthCompletedEvent.class));
        }

        @Test
        @DisplayName("3DS 启用 + Challenge → 创建记录、调用 ACS、不发布事件")
        void threeDsEnabled_challenge_createsRecordNoEvent() {
            PaymentOrder order = testOrder();
            RiskAssessor.DeviceInfo device = testDeviceInfo();
            ThreeDsConfig config = enabledConfig();

            when(paymentOrderRepository.findById(1L)).thenReturn(Optional.of(order));
            when(configService.getConfig("tenant-001", 200L)).thenReturn(config);
            when(authRecordRepository.save(any(ThreeDsAuthRecord.class)))
                    .thenAnswer(invocation -> {
                        ThreeDsAuthRecord record = invocation.getArgument(0);
                        record.setId(100L);
                        return record;
                    });
            when(threeDsServer.initiateAuth(order, device, config)).thenReturn(challengeResult());

            ThreeDsServer.ThreeDsAuthResult result =
                    threeDsService.initiateAuth(1L, device);

            assertEquals(TransStatus.C, result.getTransStatus(),
                    "Challenge 应返回 transStatus=C");
            assertEquals(AuthStatus.INITIATED, result.getAuthStatus());

            // 验证调用 ACS
            verify(threeDsServer, times(1)).initiateAuth(order, device, config);

            // Challenge 流程（INITIATED 状态）不应发布事件
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("订单不存在 → 抛出 IllegalArgumentException")
        void orderNotFound_throwsException() {
            RiskAssessor.DeviceInfo device = testDeviceInfo();

            when(paymentOrderRepository.findById(999L)).thenReturn(Optional.empty());

            assertThrows(IllegalArgumentException.class,
                    () -> threeDsService.initiateAuth(999L, device),
                    "订单不存在应抛出 IllegalArgumentException");
        }

        @Test
        @DisplayName("认证记录创建时设置正确的租户和商户ID")
        void authRecordCreatedWithCorrectTenantAndMerchant() {
            PaymentOrder order = testOrder();
            RiskAssessor.DeviceInfo device = testDeviceInfo();
            ThreeDsConfig config = enabledConfig();

            when(paymentOrderRepository.findById(1L)).thenReturn(Optional.of(order));
            when(configService.getConfig("tenant-001", 200L)).thenReturn(config);
            when(authRecordRepository.save(any(ThreeDsAuthRecord.class)))
                    .thenAnswer(invocation -> {
                        ThreeDsAuthRecord record = invocation.getArgument(0);
                        record.setId(100L);
                        return record;
                    });
            when(threeDsServer.initiateAuth(order, device, config)).thenReturn(frictionlessResult());

            threeDsService.initiateAuth(1L, device);

            // 验证创建记录时设置了正确的租户和商户ID
            verify(authRecordRepository, atLeastOnce()).save(argThat(record ->
                    "tenant-001".equals(record.getTenantId()) &&
                    record.getMerchantId().equals(200L) &&
                    record.getPaymentOrderId().equals(1L)));
        }
    }

    // --- completeAuth 测试 ---

    @Nested
    @DisplayName("completeAuth - 完成 Challenge 认证")
    class CompleteAuth {

        @Test
        @DisplayName("INITIATED 状态记录 + 有效挑战结果 → 认证完成")
        void initiatedRecord_validChallenge_completesAuth() {
            ThreeDsAuthRecord record = new ThreeDsAuthRecord();
            record.setId(100L);
            record.setTenantId("tenant-001");
            record.setMerchantId(200L);
            record.setPaymentOrderId(1L);
            record.setAuthStatus(AuthStatus.INITIATED);
            record.setTransStatus(TransStatus.C);
            record.setRiskScore(80);

            when(authRecordRepository.findById(100L)).thenReturn(Optional.of(record));
            when(threeDsServer.completeChallenge(record, "123456"))
                    .thenReturn(challengeCompletedResult());
            when(authRecordRepository.save(any())).thenReturn(record);

            ThreeDsServer.ThreeDsAuthResult result =
                    threeDsService.completeAuth(100L, "123456");

            assertEquals(TransStatus.Y, result.getTransStatus(),
                    "有效挑战结果应返回 transStatus=Y");
            assertEquals(AuthStatus.COMPLETED, result.getAuthStatus());

            // 验证调用 ACS 完成 Challenge
            verify(threeDsServer, times(1)).completeChallenge(record, "123456");

            // 验证更新认证记录
            verify(authRecordRepository, atLeastOnce()).save(argThat(r ->
                    r.getAuthStatus() == AuthStatus.COMPLETED &&
                    r.getTransStatus() == TransStatus.Y));

            // 验证发布事件
            verify(eventPublisher, times(1)).publishEvent(any(ThreeDsAuthCompletedEvent.class));
        }

        @Test
        @DisplayName("非 INITIATED 状态记录 → 返回失败结果，不调用 ACS")
        void nonInitiatedRecord_returnsFailed() {
            ThreeDsAuthRecord record = new ThreeDsAuthRecord();
            record.setId(100L);
            record.setAuthStatus(AuthStatus.COMPLETED); // 已完成，非 INITIATED
            record.setTransStatus(TransStatus.Y);

            when(authRecordRepository.findById(100L)).thenReturn(Optional.of(record));

            ThreeDsServer.ThreeDsAuthResult result =
                    threeDsService.completeAuth(100L, "123456");

            assertEquals(TransStatus.N, result.getTransStatus(),
                    "非 INITIATED 状态应返回 transStatus=N");
            assertEquals(AuthStatus.FAILED, result.getAuthStatus(),
                    "非 INITIATED 状态认证应为 FAILED");
            assertEquals("3DS_CHALLENGE_INVALID", result.getErrorCode());

            // 不应调用 ACS
            verify(threeDsServer, never()).completeChallenge(any(), any());
            // 不应发布事件
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("认证记录不存在 → 抛出 IllegalArgumentException")
        void authRecordNotFound_throwsException() {
            when(authRecordRepository.findById(999L)).thenReturn(Optional.empty());

            assertThrows(IllegalArgumentException.class,
                    () -> threeDsService.completeAuth(999L, "123456"),
                    "认证记录不存在应抛出 IllegalArgumentException");
        }
    }

    // --- getAuthStatus 测试 ---

    @Nested
    @DisplayName("getAuthStatus - 查询认证状态")
    class GetAuthStatus {

        @Test
        @DisplayName("存在认证记录 → 返回记录")
        void existingRecord_returnsRecord() {
            ThreeDsAuthRecord record = new ThreeDsAuthRecord();
            record.setId(100L);
            record.setPaymentOrderId(1L);
            record.setAuthStatus(AuthStatus.COMPLETED);
            record.setTransStatus(TransStatus.Y);

            when(authRecordRepository.findByPaymentOrderId(1L)).thenReturn(Optional.of(record));

            Optional<ThreeDsAuthRecord> result = threeDsService.getAuthStatus(1L);

            assertTrue(result.isPresent(), "应返回认证记录");
            assertEquals(AuthStatus.COMPLETED, result.get().getAuthStatus());
            assertEquals(TransStatus.Y, result.get().getTransStatus());
        }

        @Test
        @DisplayName("无认证记录 → 返回空 Optional")
        void noRecord_returnsEmpty() {
            when(authRecordRepository.findByPaymentOrderId(999L)).thenReturn(Optional.empty());

            Optional<ThreeDsAuthRecord> result = threeDsService.getAuthStatus(999L);

            assertTrue(result.isEmpty(), "无认证记录应返回空 Optional");
        }

        @Test
        @DisplayName("调用 authRecordRepository.findByPaymentOrderId")
        void callsRepositoryFindByPaymentOrderId() {
            when(authRecordRepository.findByPaymentOrderId(1L)).thenReturn(Optional.empty());

            threeDsService.getAuthStatus(1L);

            verify(authRecordRepository, times(1)).findByPaymentOrderId(1L);
        }
    }
}