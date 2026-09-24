package org.nexus.gateway.onboarding;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.nexus.gateway.apikey.ApiKeyService;
import org.nexus.gateway.clearing.SettlementPeriod;
import org.nexus.gateway.limit.MerchantLimitConfig;
import org.nexus.gateway.limit.MerchantLimitConfigRepository;
import org.nexus.gateway.model.Merchant;
import org.nexus.gateway.repository.MerchantRepository;
import org.nexus.gateway.settlement.MerchantSettlementConfig;
import org.nexus.gateway.settlement.MerchantSettlementConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 商户入驻审核服务 — 管理入驻申请的完整生命周期。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>{@link #submitApplication}：提交入驻申请，验证必填字段</li>
 *   <li>{@link #startReview}：审核人员领取申请（PENDING → REVIEWING）</li>
 *   <li>{@link #approveApplication}：审核通过，自动执行开通流程</li>
 *   <li>{@link #rejectApplication}：审核拒绝，记录拒绝原因</li>
 *   <li>{@link #suspendMerchant}：暂停已开通商户的服务</li>
 *   <li>{@link #getApplicationStatus}：查询申请状态</li>
 *   <li>{@link #listPendingApplications}：列出待审核申请</li>
 * </ul>
 *
 * <p>KYC 验证（简化版）：审核时验证营业执照号格式与联系方式有效性。
 * 营业执照号须为 18 位统一社会信用代码格式；联系方式须包含有效邮箱与电话。</p>
 *
 * <p>审核通过后自动开通流程：</p>
 * <ol>
 *   <li>创建商户账号（Merchant 实体，状态 VERIFIED）</li>
 *   <li>生成 API Key（复用 ApiKeyService.createApiKey，scopes=PAYMENTS,REFUNDS）</li>
 *   <li>配置默认结算周期（T+1）</li>
 *   <li>配置默认限额策略（单笔上限 10万，日累计 50万）</li>
 * </ol>
 */
@Service
public class MerchantReviewService {

    private static final Logger log = LoggerFactory.getLogger(MerchantReviewService.class);

    /** 统一社会信用代码格式：18位（数字+大写字母） */
    private static final Pattern LICENSE_NO_PATTERN = Pattern.compile("^[0-9A-Z]{18}$");

    /** 邮箱格式简化校验 */
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /** 电话格式：支持国内手机号与座机 */
    private static final Pattern PHONE_PATTERN = Pattern.compile("^[0-9+\\-\\s()]{7,20}$");

    /** 默认单笔最大金额（10万 NEX） */
    private static final BigDecimal DEFAULT_SINGLE_MAX = new BigDecimal("100000");

    /** 默认日累计最大金额（50万 NEX） */
    private static final BigDecimal DEFAULT_DAILY_MAX = new BigDecimal("500000");

    /** 默认 API Key 权限范围 */
    private static final String DEFAULT_API_KEY_SCOPES = "PAYMENTS,REFUNDS";

    private final MerchantApplicationRepository applicationRepository;
    private final MerchantRepository merchantRepository;
    private final ApiKeyService apiKeyService;
    private final MerchantSettlementConfigRepository settlementConfigRepository;
    private final MerchantLimitConfigRepository limitConfigRepository;

    public MerchantReviewService(MerchantApplicationRepository applicationRepository,
                                  MerchantRepository merchantRepository,
                                  ApiKeyService apiKeyService,
                                  MerchantSettlementConfigRepository settlementConfigRepository,
                                  MerchantLimitConfigRepository limitConfigRepository) {
        this.applicationRepository = applicationRepository;
        this.merchantRepository = merchantRepository;
        this.apiKeyService = apiKeyService;
        this.settlementConfigRepository = settlementConfigRepository;
        this.limitConfigRepository = limitConfigRepository;
    }

    /**
     * 提交入驻申请。
     *
     * <p>验证必填字段（商户名称、联系人、邮箱、电话、业务类型、营业执照号），
     * 创建申请记录，初始状态为 PENDING。</p>
     *
     * @param request 申请信息
     * @return 创建的申请记录
     * @throws IllegalArgumentException 必填字段缺失或格式无效
     */
    @Transactional
    public MerchantApplication submitApplication(ApplicationRequest request) {
        validateRequiredFields(request);

        MerchantApplication application = new MerchantApplication();
        application.setMerchantName(request.getMerchantName());
        application.setContactName(request.getContactName());
        application.setContactEmail(request.getContactEmail());
        application.setContactPhone(request.getContactPhone());
        application.setBusinessType(request.getBusinessType());
        application.setBusinessLicenseNo(request.getBusinessLicenseNo());
        application.setBusinessLicenseUrl(request.getBusinessLicenseUrl());
        application.setWebsiteUrl(request.getWebsiteUrl());
        application.setDescription(request.getDescription());
        application.setStatus(ApplicationStatus.PENDING);
        application.setSubmittedAt(LocalDateTime.now());

        application = applicationRepository.save(application);
        log.info("商户入驻申请已提交: id={}, merchantName={}, businessType={}",
                application.getId(), application.getMerchantName(), application.getBusinessType());

        return application;
    }

    /**
     * 审核人员领取申请（PENDING → REVIEWING）。
     *
     * @param applicationId 申请 ID
     * @param reviewerId    审核人员 ID
     * @return 更新后的申请记录
     * @throws IllegalArgumentException 申请不存在
     * @throws IllegalStateException    申请状态非 PENDING
     */
    @Transactional
    public MerchantApplication startReview(Long applicationId, String reviewerId) {
        MerchantApplication application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("申请不存在: " + applicationId));

        if (application.getStatus() != ApplicationStatus.PENDING) {
            throw new IllegalStateException(
                    "仅 PENDING 状态的申请可领取审核，当前状态: " + application.getStatus());
        }

        application.setStatus(ApplicationStatus.REVIEWING);
        application.setReviewerId(reviewerId);
        application.setReviewedAt(LocalDateTime.now());

        application = applicationRepository.save(application);
        log.info("审核人员领取申请: id={}, reviewerId={}", applicationId, reviewerId);

        return application;
    }

    /**
     * 审核通过 — 自动执行开通流程。
     *
     * <p>开通流程：</p>
     * <ol>
     *   <li>KYC 验证（简化版：营业执照号格式 + 联系方式）</li>
     *   <li>创建商户账号（Merchant，状态 VERIFIED）</li>
     *   <li>生成 API Key（scopes=PAYMENTS,REFUNDS）</li>
     *   <li>配置默认结算周期 T+1</li>
     *   <li>配置默认限额策略</li>
     * </ol>
     *
     * @param applicationId 申请 ID
     * @param reviewerId    审核人员 ID
     * @param reviewComment 审核意见
     * @return 更新后的申请记录（含 merchantId）
     * @throws IllegalArgumentException 申请不存在
     * @throws IllegalStateException    申请状态非 REVIEWING 或 KYC 验证失败
     */
    @Transactional
    public MerchantApplication approveApplication(Long applicationId, String reviewerId, String reviewComment) {
        MerchantApplication application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("申请不存在: " + applicationId));

        if (application.getStatus() != ApplicationStatus.REVIEWING) {
            throw new IllegalStateException(
                    "仅 REVIEWING 状态的申请可通过审核，当前状态: " + application.getStatus());
        }

        // KYC 验证（简化版）
        KycResult kycResult = performKycVerification(application);
        if (!kycResult.passed()) {
            application.setStatus(ApplicationStatus.REJECTED);
            application.setReviewComment("KYC 验证失败: " + kycResult.reason());
            application = applicationRepository.save(application);
            log.warn("KYC 验证失败，申请被拒绝: id={}, reason={}", applicationId, kycResult.reason());
            throw new IllegalStateException("KYC 验证失败: " + kycResult.reason());
        }

        // 自动开通流程
        OnboardingResult onboardingResult = executeOnboarding(application);

        application.setStatus(ApplicationStatus.APPROVED);
        application.setReviewerId(reviewerId);
        application.setReviewComment(reviewComment);
        application.setApprovedAt(LocalDateTime.now());
        application.setMerchantId(onboardingResult.merchantId());

        application = applicationRepository.save(application);
        log.info("商户入驻申请已通过: id={}, merchantId={}, apiKeyId={}",
                applicationId, onboardingResult.merchantId(), onboardingResult.apiKeyId());

        return application;
    }

    /**
     * 审核拒绝。
     *
     * @param applicationId 申请 ID
     * @param reviewerId    审核人员 ID
     * @param reviewComment 拒绝原因
     * @return 更新后的申请记录
     * @throws IllegalArgumentException 申请不存在
     * @throws IllegalStateException    申请状态非 REVIEWING
     */
    @Transactional
    public MerchantApplication rejectApplication(Long applicationId, String reviewerId, String reviewComment) {
        MerchantApplication application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("申请不存在: " + applicationId));

        if (application.getStatus() != ApplicationStatus.REVIEWING) {
            throw new IllegalStateException(
                    "仅 REVIEWING 状态的申请可拒绝，当前状态: " + application.getStatus());
        }

        application.setStatus(ApplicationStatus.REJECTED);
        application.setReviewerId(reviewerId);
        application.setReviewComment(reviewComment);

        application = applicationRepository.save(application);
        log.info("商户入驻申请被拒绝: id={}, reason={}", applicationId, reviewComment);

        return application;
    }

    /**
     * 暂停已开通商户的服务（APPROVED → SUSPENDED）。
     *
     * @param applicationId 申请 ID
     * @param reason        暂停原因
     * @return 更新后的申请记录
     * @throws IllegalArgumentException 申请不存在
     * @throws IllegalStateException    申请状态非 APPROVED
     */
    @Transactional
    public MerchantApplication suspendMerchant(Long applicationId, String reason) {
        MerchantApplication application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("申请不存在: " + applicationId));

        if (application.getStatus() != ApplicationStatus.APPROVED) {
            throw new IllegalStateException(
                    "仅 APPROVED 状态的申请可暂停，当前状态: " + application.getStatus());
        }

        application.setStatus(ApplicationStatus.SUSPENDED);
        application.setReviewComment(reason);

        application = applicationRepository.save(application);
        log.info("商户服务已暂停: id={}, merchantId={}, reason={}",
                applicationId, application.getMerchantId(), reason);

        return application;
    }

    /**
     * 查询申请状态。
     *
     * @param applicationId 申请 ID
     * @return 申请记录（含状态信息）
     * @throws IllegalArgumentException 申请不存在
     */
    @Transactional(readOnly = true)
    public MerchantApplication getApplicationStatus(Long applicationId) {
        return applicationRepository.findById(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("申请不存在: " + applicationId));
    }

    /**
     * 列出待审核申请（PENDING 状态，按提交时间升序）。
     *
     * @return 待审核申请列表
     */
    @Transactional(readOnly = true)
    public List<MerchantApplication> listPendingApplications() {
        return applicationRepository.findByStatusOrderBySubmittedAtAsc(ApplicationStatus.PENDING);
    }

    /**
     * 列出指定状态的申请。
     *
     * @param status 申请状态
     * @return 申请列表
     */
    @Transactional(readOnly = true)
    public List<MerchantApplication> listApplicationsByStatus(ApplicationStatus status) {
        return applicationRepository.findByStatus(status);
    }

    // --- 内部方法 ---

    /**
     * 验证申请必填字段。
     */
    private void validateRequiredFields(ApplicationRequest request) {
        if (request.getMerchantName() == null || request.getMerchantName().isBlank()) {
            throw new IllegalArgumentException("商户名称不能为空");
        }
        if (request.getContactName() == null || request.getContactName().isBlank()) {
            throw new IllegalArgumentException("联系人姓名不能为空");
        }
        if (request.getContactEmail() == null || !EMAIL_PATTERN.matcher(request.getContactEmail()).matches()) {
            throw new IllegalArgumentException("联系人邮箱格式无效");
        }
        if (request.getContactPhone() == null || !PHONE_PATTERN.matcher(request.getContactPhone()).matches()) {
            throw new IllegalArgumentException("联系人电话格式无效");
        }
        if (request.getBusinessType() == null || request.getBusinessType().isBlank()) {
            throw new IllegalArgumentException("业务类型不能为空");
        }
        if (request.getBusinessLicenseNo() == null || !LICENSE_NO_PATTERN.matcher(request.getBusinessLicenseNo()).matches()) {
            throw new IllegalArgumentException("营业执照编号格式无效（须为18位统一社会信用代码）");
        }
    }

    /**
     * KYC 验证（简化版） — 验证营业执照号格式与联系方式。
     *
     * @param application 申请记录
     * @return KYC 验证结果
     */
    private KycResult performKycVerification(MerchantApplication application) {
        // 营业执照号格式验证
        if (!LICENSE_NO_PATTERN.matcher(application.getBusinessLicenseNo()).matches()) {
            return new KycResult(false, "营业执照编号格式无效");
        }

        // 联系邮箱验证
        if (!EMAIL_PATTERN.matcher(application.getContactEmail()).matches()) {
            return new KycResult(false, "联系邮箱格式无效");
        }

        // 联系电话验证
        if (!PHONE_PATTERN.matcher(application.getContactPhone()).matches()) {
            return new KycResult(false, "联系电话格式无效");
        }

        log.info("KYC 验证通过: applicationId={}, licenseNo={}",
                application.getId(), application.getBusinessLicenseNo());
        return new KycResult(true, null);
    }

    /**
     * 执行自动开通流程。
     *
     * <p>步骤：</p>
     * <ol>
     *   <li>创建商户账号（Merchant，状态 VERIFIED）</li>
     *   <li>生成 API Key</li>
     *   <li>配置默认结算周期 T+1</li>
     *   <li>配置默认限额策略</li>
     * </ol>
     */
    private OnboardingResult executeOnboarding(MerchantApplication application) {
        // 1. 创建商户账号
        Merchant merchant = createMerchant(application);

        // 2. 生成 API Key
        ApiKeyService.CreateApiKeyResult apiKeyResult = apiKeyService.createApiKey(
                String.valueOf(merchant.getId()),
                DEFAULT_API_KEY_SCOPES,
                "入驻自动生成",
                null  // 永不过期
        );

        // 3. 配置默认结算周期 T+1
        MerchantSettlementConfig settlementConfig = new MerchantSettlementConfig();
        settlementConfig.setMerchantId(merchant.getId());
        settlementConfig.setSettlementPeriod(SettlementPeriod.T1);
        settlementConfig.setAutoSettleEnabled(true);
        settlementConfigRepository.save(settlementConfig);

        // 4. 配置默认限额策略
        MerchantLimitConfig limitConfig = new MerchantLimitConfig();
        limitConfig.setMerchantId(merchant.getId());
        limitConfig.setSingleTransactionMaxAmount(DEFAULT_SINGLE_MAX);
        limitConfig.setDailyAccumulatedMaxAmount(DEFAULT_DAILY_MAX);
        limitConfig.setActive(true);
        limitConfigRepository.save(limitConfig);

        log.info("商户自动开通完成: merchantId={}, merchantCode={}, apiKeyId={}, settlement=T1",
                merchant.getId(), merchant.getMerchantCode(), apiKeyResult.getApiKey().getKeyId());

        return new OnboardingResult(merchant.getId(), apiKeyResult.getApiKey().getKeyId());
    }

    /**
     * 创建商户账号。
     */
    private Merchant createMerchant(MerchantApplication application) {
        Merchant merchant = new Merchant();
        merchant.setMerchantCode(generateMerchantCode());
        merchant.setMerchantName(application.getMerchantName());
        merchant.setEmail(application.getContactEmail());
        merchant.setSettlementAddress(generateSettlementAddress());
        merchant.setVerificationStatus(Merchant.VerificationStatus.VERIFIED);

        return merchantRepository.save(merchant);
    }

    /**
     * 生成商户编码：MCH + UUID 前12位。
     */
    private String generateMerchantCode() {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        return "MCH" + uuid.substring(0, 12);
    }

    /**
     * 生成结算钱包地址（占位地址，实际应由钱包服务分配）。
     */
    private String generateSettlementAddress() {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        return "0x" + uuid; // UUID 去掉横线后为 32 字符，加上 0x 前缀共 34 字符
    }

    // --- DTO ---

    /**
     * 入驻申请请求 DTO。
     *
     * <p>P1-3：添加 Bean Validation 注解，由 Controller 的 {@code @Valid} 触发，
     * 在进入业务逻辑前拦截缺失/非法参数。</p>
     */
    public static class ApplicationRequest {

        @NotBlank(message = "merchantName must not be blank")
        @Size(max = 128, message = "merchantName must not exceed 128 characters")
        private String merchantName;

        @NotBlank(message = "contactName must not be blank")
        @Size(max = 64, message = "contactName must not exceed 64 characters")
        private String contactName;

        @NotBlank(message = "contactEmail must not be blank")
        @Email(message = "contactEmail must be a valid email address")
        @Size(max = 128, message = "contactEmail must not exceed 128 characters")
        private String contactEmail;

        @NotBlank(message = "contactPhone must not be blank")
        @Size(max = 32, message = "contactPhone must not exceed 32 characters")
        private String contactPhone;

        @NotBlank(message = "businessType must not be blank")
        @Size(max = 64, message = "businessType must not exceed 64 characters")
        private String businessType;

        @NotBlank(message = "businessLicenseNo must not be blank")
        @Size(max = 128, message = "businessLicenseNo must not exceed 128 characters")
        private String businessLicenseNo;

        @Size(max = 512, message = "businessLicenseUrl must not exceed 512 characters")
        private String businessLicenseUrl;

        @Size(max = 512, message = "websiteUrl must not exceed 512 characters")
        private String websiteUrl;

        @Size(max = 1024, message = "description must not exceed 1024 characters")
        private String description;

        public String getMerchantName() { return merchantName; }
        public void setMerchantName(String merchantName) { this.merchantName = merchantName; }

        public String getContactName() { return contactName; }
        public void setContactName(String contactName) { this.contactName = contactName; }

        public String getContactEmail() { return contactEmail; }
        public void setContactEmail(String contactEmail) { this.contactEmail = contactEmail; }

        public String getContactPhone() { return contactPhone; }
        public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }

        public String getBusinessType() { return businessType; }
        public void setBusinessType(String businessType) { this.businessType = businessType; }

        public String getBusinessLicenseNo() { return businessLicenseNo; }
        public void setBusinessLicenseNo(String businessLicenseNo) { this.businessLicenseNo = businessLicenseNo; }

        public String getBusinessLicenseUrl() { return businessLicenseUrl; }
        public void setBusinessLicenseUrl(String businessLicenseUrl) { this.businessLicenseUrl = businessLicenseUrl; }

        public String getWebsiteUrl() { return websiteUrl; }
        public void setWebsiteUrl(String websiteUrl) { this.websiteUrl = websiteUrl; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }

    /**
     * KYC 验证结果。
     */
    private record KycResult(boolean passed, String reason) {}

    /**
     * 开通流程结果。
     */
    private record OnboardingResult(Long merchantId, String apiKeyId) {}
}