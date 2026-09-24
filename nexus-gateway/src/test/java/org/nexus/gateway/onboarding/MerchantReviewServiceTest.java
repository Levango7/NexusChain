package org.nexus.gateway.onboarding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.gateway.apikey.ApiKey;
import org.nexus.gateway.apikey.ApiKeyService;
import org.nexus.gateway.apikey.ApiKeyStatus;
import org.nexus.gateway.clearing.SettlementPeriod;
import org.nexus.gateway.limit.MerchantLimitConfig;
import org.nexus.gateway.limit.MerchantLimitConfigRepository;
import org.nexus.gateway.model.Merchant;
import org.nexus.gateway.repository.MerchantRepository;
import org.nexus.gateway.settlement.MerchantSettlementConfig;
import org.nexus.gateway.settlement.MerchantSettlementConfigRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@link MerchantReviewService} 单元测试。
 *
 * <p>覆盖：申请提交（字段验证）、审核领取、审核通过（自动开通流程）、
 * 审核拒绝、暂停商户、查询状态、列出待审核申请等核心逻辑。
 * 使用 Mockito mock 所有 Repository 和 ApiKeyService，不依赖数据库。</p>
 */
@ExtendWith(MockitoExtension.class)
class MerchantReviewServiceTest {

    @Mock
    private MerchantApplicationRepository applicationRepository;

    @Mock
    private MerchantRepository merchantRepository;

    @Mock
    private ApiKeyService apiKeyService;

    @Mock
    private MerchantSettlementConfigRepository settlementConfigRepository;

    @Mock
    private MerchantLimitConfigRepository limitConfigRepository;

    private MerchantReviewService service;

    @BeforeEach
    void setUp() {
        service = new MerchantReviewService(
                applicationRepository,
                merchantRepository,
                apiKeyService,
                settlementConfigRepository,
                limitConfigRepository
        );
    }

    // === 申请提交 ===

    @Test
    @DisplayName("提交申请：必填字段完整时创建成功")
    void submitApplication_validFields_createsSuccessfully() {
        MerchantReviewService.ApplicationRequest request = buildValidRequest();

        when(applicationRepository.save(any(MerchantApplication.class))).thenAnswer(inv -> {
            MerchantApplication app = inv.getArgument(0);
            app.setId(1L);
            return app;
        });

        MerchantApplication result = service.submitApplication(request);

        assertNotNull(result);
        assertEquals(1L, result.getId());
        assertEquals("测试商户有限公司", result.getMerchantName());
        assertEquals(ApplicationStatus.PENDING, result.getStatus());
        assertNotNull(result.getSubmittedAt());

        ArgumentCaptor<MerchantApplication> captor = ArgumentCaptor.forClass(MerchantApplication.class);
        verify(applicationRepository).save(captor.capture());
        assertEquals(ApplicationStatus.PENDING, captor.getValue().getStatus());
    }

    @Test
    @DisplayName("提交申请：商户名称为空时抛异常")
    void submitApplication_emptyMerchantName_throwsException() {
        MerchantReviewService.ApplicationRequest request = buildValidRequest();
        request.setMerchantName("");

        assertThrows(IllegalArgumentException.class, () -> service.submitApplication(request));
    }

    @Test
    @DisplayName("提交申请：邮箱格式无效时抛异常")
    void submitApplication_invalidEmail_throwsException() {
        MerchantReviewService.ApplicationRequest request = buildValidRequest();
        request.setContactEmail("invalid-email");

        assertThrows(IllegalArgumentException.class, () -> service.submitApplication(request));
    }

    @Test
    @DisplayName("提交申请：电话格式无效时抛异常")
    void submitApplication_invalidPhone_throwsException() {
        MerchantReviewService.ApplicationRequest request = buildValidRequest();
        request.setContactPhone("abc");

        assertThrows(IllegalArgumentException.class, () -> service.submitApplication(request));
    }

    @Test
    @DisplayName("提交申请：营业执照编号格式无效时抛异常")
    void submitApplication_invalidLicenseNo_throwsException() {
        MerchantReviewService.ApplicationRequest request = buildValidRequest();
        request.setBusinessLicenseNo("123");

        assertThrows(IllegalArgumentException.class, () -> service.submitApplication(request));
    }

    // === 审核领取 ===

    @Test
    @DisplayName("审核领取：PENDING → REVIEWING 状态转换")
    void startReview_pendingToReviewing_statusChanged() {
        MerchantApplication application = buildApplication(1L, ApplicationStatus.PENDING);

        when(applicationRepository.findById(1L)).thenReturn(Optional.of(application));
        when(applicationRepository.save(any(MerchantApplication.class))).thenAnswer(inv -> inv.getArgument(0));

        MerchantApplication result = service.startReview(1L, "admin-001");

        assertEquals(ApplicationStatus.REVIEWING, result.getStatus());
        assertEquals("admin-001", result.getReviewerId());
        assertNotNull(result.getReviewedAt());
    }

    @Test
    @DisplayName("审核领取：申请不存在时抛异常")
    void startReview_notFound_throwsException() {
        when(applicationRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.startReview(999L, "admin-001"));
    }

    @Test
    @DisplayName("审核领取：非 PENDING 状态时抛异常")
    void startReview_nonPendingStatus_throwsException() {
        MerchantApplication application = buildApplication(1L, ApplicationStatus.REVIEWING);

        when(applicationRepository.findById(1L)).thenReturn(Optional.of(application));

        assertThrows(IllegalStateException.class, () -> service.startReview(1L, "admin-001"));
    }

    // === 审核通过 ===

    @Test
    @DisplayName("审核通过：自动创建商户账号、生成API Key、配置结算周期与限额")
    void approveApplication_validFlow_executesOnboarding() {
        MerchantApplication application = buildApplication(1L, ApplicationStatus.REVIEWING);

        when(applicationRepository.findById(1L)).thenReturn(Optional.of(application));
        when(merchantRepository.save(any(Merchant.class))).thenAnswer(inv -> {
            Merchant m = inv.getArgument(0);
            m.setId(100L);
            return m;
        });

        ApiKey mockApiKey = new ApiKey();
        mockApiKey.setId(1L);
        mockApiKey.setKeyId("ak_live_test1234567");
        mockApiKey.setMerchantId("100");
        mockApiKey.setScopes("PAYMENTS,REFUNDS");
        mockApiKey.setStatus(ApiKeyStatus.ACTIVE);
        ApiKeyService.CreateApiKeyResult apiKeyResult = new ApiKeyService.CreateApiKeyResult(mockApiKey, "plain-secret");

        when(apiKeyService.createApiKey(eq("100"), eq("PAYMENTS,REFUNDS"), anyString(), isNull()))
                .thenReturn(apiKeyResult);
        when(applicationRepository.save(any(MerchantApplication.class))).thenAnswer(inv -> inv.getArgument(0));

        MerchantApplication result = service.approveApplication(1L, "admin-001", "审核通过");

        assertEquals(ApplicationStatus.APPROVED, result.getStatus());
        assertEquals("admin-001", result.getReviewerId());
        assertEquals("审核通过", result.getReviewComment());
        assertNotNull(result.getApprovedAt());
        assertEquals(100L, result.getMerchantId());

        // 验证商户创建
        ArgumentCaptor<Merchant> merchantCaptor = ArgumentCaptor.forClass(Merchant.class);
        verify(merchantRepository).save(merchantCaptor.capture());
        Merchant savedMerchant = merchantCaptor.getValue();
        assertEquals("测试商户有限公司", savedMerchant.getMerchantName());
        assertEquals(Merchant.VerificationStatus.VERIFIED, savedMerchant.getVerificationStatus());
        assertNotNull(savedMerchant.getMerchantCode());
        assertTrue(savedMerchant.getMerchantCode().startsWith("MCH"));

        // 验证 API Key 生成
        verify(apiKeyService).createApiKey(eq("100"), eq("PAYMENTS,REFUNDS"), eq("入驻自动生成"), isNull());

        // 验证结算周期配置
        ArgumentCaptor<MerchantSettlementConfig> settlementCaptor = ArgumentCaptor.forClass(MerchantSettlementConfig.class);
        verify(settlementConfigRepository).save(settlementCaptor.capture());
        assertEquals(100L, settlementCaptor.getValue().getMerchantId());
        assertEquals(SettlementPeriod.T1, settlementCaptor.getValue().getSettlementPeriod());
        assertTrue(settlementCaptor.getValue().isAutoSettleEnabled());

        // 验证限额配置
        ArgumentCaptor<MerchantLimitConfig> limitCaptor = ArgumentCaptor.forClass(MerchantLimitConfig.class);
        verify(limitConfigRepository).save(limitCaptor.capture());
        MerchantLimitConfig savedLimit = limitCaptor.getValue();
        assertEquals(100L, savedLimit.getMerchantId());
        assertEquals(new BigDecimal("100000"), savedLimit.getSingleTransactionMaxAmount());
        assertEquals(new BigDecimal("500000"), savedLimit.getDailyAccumulatedMaxAmount());
        assertTrue(savedLimit.isActive());
    }

    @Test
    @DisplayName("审核通过：非 REVIEWING 状态时抛异常")
    void approveApplication_nonReviewingStatus_throwsException() {
        MerchantApplication application = buildApplication(1L, ApplicationStatus.PENDING);

        when(applicationRepository.findById(1L)).thenReturn(Optional.of(application));

        assertThrows(IllegalStateException.class, () -> service.approveApplication(1L, "admin-001", "通过"));
    }

    @Test
    @DisplayName("审核通过：KYC 验证失败（营业执照号格式无效）时拒绝申请")
    void approveApplication_kycFailed_licenseNoInvalid() {
        MerchantApplication application = buildApplication(1L, ApplicationStatus.REVIEWING);
        application.setBusinessLicenseNo("INVALID123"); // 非18位格式

        when(applicationRepository.findById(1L)).thenReturn(Optional.of(application));
        when(applicationRepository.save(any(MerchantApplication.class))).thenAnswer(inv -> inv.getArgument(0));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.approveApplication(1L, "admin-001", "通过"));

        assertTrue(ex.getMessage().contains("KYC 验证失败"));
        verify(merchantRepository, never()).save(any());
        verify(apiKeyService, never()).createApiKey(anyString(), anyString(), anyString(), any());
    }

    // === 审核拒绝 ===

    @Test
    @DisplayName("审核拒绝：REVIEWING → REJECTED 状态转换")
    void rejectApplication_reviewingToRejected_statusChanged() {
        MerchantApplication application = buildApplication(1L, ApplicationStatus.REVIEWING);

        when(applicationRepository.findById(1L)).thenReturn(Optional.of(application));
        when(applicationRepository.save(any(MerchantApplication.class))).thenAnswer(inv -> inv.getArgument(0));

        MerchantApplication result = service.rejectApplication(1L, "admin-001", "资质不符");

        assertEquals(ApplicationStatus.REJECTED, result.getStatus());
        assertEquals("admin-001", result.getReviewerId());
        assertEquals("资质不符", result.getReviewComment());
    }

    @Test
    @DisplayName("审核拒绝：非 REVIEWING 状态时抛异常")
    void rejectApplication_nonReviewingStatus_throwsException() {
        MerchantApplication application = buildApplication(1L, ApplicationStatus.PENDING);

        when(applicationRepository.findById(1L)).thenReturn(Optional.of(application));

        assertThrows(IllegalStateException.class, () -> service.rejectApplication(1L, "admin-001", "拒绝"));
    }

    // === 暂停商户 ===

    @Test
    @DisplayName("暂停商户：APPROVED → SUSPENDED 状态转换")
    void suspendMerchant_approvedToSuspended_statusChanged() {
        MerchantApplication application = buildApplication(1L, ApplicationStatus.APPROVED);
        application.setMerchantId(100L);

        when(applicationRepository.findById(1L)).thenReturn(Optional.of(application));
        when(applicationRepository.save(any(MerchantApplication.class))).thenAnswer(inv -> inv.getArgument(0));

        MerchantApplication result = service.suspendMerchant(1L, "违规操作");

        assertEquals(ApplicationStatus.SUSPENDED, result.getStatus());
        assertEquals("违规操作", result.getReviewComment());
    }

    @Test
    @DisplayName("暂停商户：非 APPROVED 状态时抛异常")
    void suspendMerchant_nonApprovedStatus_throwsException() {
        MerchantApplication application = buildApplication(1L, ApplicationStatus.PENDING);

        when(applicationRepository.findById(1L)).thenReturn(Optional.of(application));

        assertThrows(IllegalStateException.class, () -> service.suspendMerchant(1L, "违规"));
    }

    // === 查询状态 ===

    @Test
    @DisplayName("查询申请状态：存在时返回申请记录")
    void getApplicationStatus_exists_returnsApplication() {
        MerchantApplication application = buildApplication(1L, ApplicationStatus.PENDING);

        when(applicationRepository.findById(1L)).thenReturn(Optional.of(application));

        MerchantApplication result = service.getApplicationStatus(1L);

        assertNotNull(result);
        assertEquals(1L, result.getId());
    }

    @Test
    @DisplayName("查询申请状态：不存在时抛异常")
    void getApplicationStatus_notFound_throwsException() {
        when(applicationRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.getApplicationStatus(999L));
    }

    // === 列出待审核申请 ===

    @Test
    @DisplayName("列出待审核申请：返回 PENDING 状态列表")
    void listPendingApplications_returnsPendingList() {
        MerchantApplication app1 = buildApplication(1L, ApplicationStatus.PENDING);
        MerchantApplication app2 = buildApplication(2L, ApplicationStatus.PENDING);

        when(applicationRepository.findByStatusOrderBySubmittedAtAsc(ApplicationStatus.PENDING))
                .thenReturn(List.of(app1, app2));

        List<MerchantApplication> result = service.listPendingApplications();

        assertEquals(2, result.size());
        assertEquals(ApplicationStatus.PENDING, result.get(0).getStatus());
        assertEquals(ApplicationStatus.PENDING, result.get(1).getStatus());
    }

    @Test
    @DisplayName("按状态列出申请：返回指定状态列表")
    void listApplicationsByStatus_returnsFilteredList() {
        MerchantApplication app1 = buildApplication(1L, ApplicationStatus.APPROVED);

        when(applicationRepository.findByStatus(ApplicationStatus.APPROVED))
                .thenReturn(List.of(app1));

        List<MerchantApplication> result = service.listApplicationsByStatus(ApplicationStatus.APPROVED);

        assertEquals(1, result.size());
        assertEquals(ApplicationStatus.APPROVED, result.get(0).getStatus());
    }

    // === 辅助方法 ===

    private MerchantReviewService.ApplicationRequest buildValidRequest() {
        MerchantReviewService.ApplicationRequest request = new MerchantReviewService.ApplicationRequest();
        request.setMerchantName("测试商户有限公司");
        request.setContactName("张三");
        request.setContactEmail("zhangsan@example.com");
        request.setContactPhone("13800138000");
        request.setBusinessType("ECOMMERCE");
        request.setBusinessLicenseNo("91110000MA00ABCD12"); // 18位统一社会信用代码
        request.setBusinessLicenseUrl("https://example.com/license.pdf");
        request.setWebsiteUrl("https://example.com");
        request.setDescription("电商平台");
        return request;
    }

    private MerchantApplication buildApplication(Long id, ApplicationStatus status) {
        MerchantApplication application = new MerchantApplication();
        application.setId(id);
        application.setMerchantName("测试商户有限公司");
        application.setContactName("张三");
        application.setContactEmail("zhangsan@example.com");
        application.setContactPhone("13800138000");
        application.setBusinessType("ECOMMERCE");
        application.setBusinessLicenseNo("91110000MA00ABCD12");
        application.setStatus(status);
        application.setSubmittedAt(LocalDateTime.now());
        return application;
    }
}