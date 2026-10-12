package org.nexus.gateway.fundreport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 商户出款去向（结算账户）服务（P0 修复 2026-10-11）。
 *
 * <p><b>要解决的问题</b>：网关"自动提现"此前只扣商户账本余额、没有任何出款动作；
 * 且资金去向无模型。资金该去哪、谁批的、是否有效，全部没有落点。</p>
 *
 * <p><b>核心规则（合规护栏）</b>：</p>
 * <ol>
 *   <li>出款去向必须是<b>商户自己绑定的</b>结算账户（BANK_CARD / ALIPAY）；</li>
 *   <li>{@code PLATFORM_SETTLEMENT}（平台侧结算账户）必须 {@code complianceEntity=true}
 *       （持牌主体）才允许绑定 —— 平台方用个人账户/非持牌主体收商户款再对外出款
 *       即"二清"，本服务用类型约束拒绝；</li>
 *   <li>状态机 {@code PENDING_VERIFY → ACTIVE / DISABLED}：绑定后需核验才可用于出款；</li>
 *   <li>户名与商户主体不一致 → 拒绝绑定（防"绑别人的卡收款"）。</li>
 * </ol>
 */
@Service
public class MerchantPayoutAccountService {

    private static final Logger log = LoggerFactory.getLogger(MerchantPayoutAccountService.class);

    private final MerchantPayoutAccountRepository repository;

    public MerchantPayoutAccountService(MerchantPayoutAccountRepository repository) {
        this.repository = repository;
    }

    /**
     * 绑定出款账户（默认进 PENDING_VERIFY，需核验后才可用于出款）。
     *
     * @param merchantId      商户 ID
     * @param accountType     账户类型
     * @param accountNo       账户标识
     * @param accountName     户名
     * @param complianceEntity 是否持牌主体（仅 PLATFORM_SETTLEMENT 有意义）
     * @throws IllegalArgumentException 违反合规护栏或参数非法时拒绝
     */
    @Transactional
    public MerchantPayoutAccount bind(Long merchantId,
                                       MerchantPayoutAccount.AccountType accountType,
                                       String accountNo, String accountName,
                                       boolean complianceEntity) {
        if (merchantId == null) {
            throw new IllegalArgumentException("绑定出款账户失败：merchantId 为空");
        }
        if (accountNo == null || accountNo.isBlank()) {
            throw new IllegalArgumentException("绑定出款账户失败：账户标识为空");
        }
        if (accountName == null || accountName.isBlank()) {
            throw new IllegalArgumentException("绑定出款账户失败：户名为空");
        }

        // 合规护栏：平台结算账户只允许持牌主体绑定（二清红线）
        if (accountType == MerchantPayoutAccount.AccountType.PLATFORM_SETTLEMENT && !complianceEntity) {
            throw new IllegalArgumentException(
                    "拒绝绑定平台结算账户：非持牌主体（complianceEntity=false）——"
                            + "平台方不得以自有账户收商户款并对外出款（二清红线）");
        }

        // 户名一致性：与商户主体不一致则拒绝（防绑他人账户）
        String merchantName = resolveMerchantName(merchantId);
        if (merchantName != null && !merchantName.equals(accountName.trim())) {
            throw new IllegalArgumentException(
                    "拒绝绑定出款账户：户名与商户主体不一致（merchant=" + merchantName
                            + ", accountName=" + accountName + "）");
        }

        MerchantPayoutAccount account = new MerchantPayoutAccount();
        account.setMerchantId(merchantId);
        account.setAccountType(accountType);
        account.setAccountNo(accountNo.trim());
        account.setAccountName(accountName.trim());
        account.setComplianceEntity(complianceEntity);
        account.setStatus(MerchantPayoutAccount.AccountStatus.PENDING_VERIFY);

        MerchantPayoutAccount saved = repository.save(account);
        log.info("[PayoutAccount] 绑定出款账户(待核验): merchantId={}, type={}, id={}",
                merchantId, accountType, saved.getId());
        return saved;
    }

    /** 核验通过 → 生效（可作出款去向）。 */
    @Transactional
    public MerchantPayoutAccount activate(Long accountId) {
        MerchantPayoutAccount account = repository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("出款账户不存在: id=" + accountId));
        account.setStatus(MerchantPayoutAccount.AccountStatus.ACTIVE);
        MerchantPayoutAccount saved = repository.save(account);
        log.info("[PayoutAccount] 出款账户生效: id={}, merchantId={}", accountId, saved.getMerchantId());
        return saved;
    }

    /** 停用（不再作为出款去向；留痕，不物理删除）。 */
    @Transactional
    public MerchantPayoutAccount disable(Long accountId) {
        MerchantPayoutAccount account = repository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("出款账户不存在: id=" + accountId));
        account.setStatus(MerchantPayoutAccount.AccountStatus.DISABLED);
        MerchantPayoutAccount saved = repository.save(account);
        log.info("[PayoutAccount] 出款账户停用: id={}, merchantId={}", accountId, saved.getMerchantId());
        return saved;
    }

    /**
     * 解析商户的**生效**出款账户（出款前置条件）。
     *
     * @return 生效账户；不存在则 Optional.empty()（调用方必须 fail-closed：不出款）
     */
    @Transactional(readOnly = true)
    public Optional<MerchantPayoutAccount> resolveActiveAccount(Long merchantId) {
        if (merchantId == null) {
            return Optional.empty();
        }
        return repository.findByMerchantIdAndStatus(merchantId,
                MerchantPayoutAccount.AccountStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public List<MerchantPayoutAccount> list(Long merchantId) {
        return repository.findByMerchantId(merchantId);
    }

    /**
     * 取商户主体名（用于户名一致性校验）。
     *
     * <p>解析顺序（2026-10-12 修 fail-open）：</p>
     * <ol>
     *   <li>显式注入的 {@code merchantNameResolver}（测试用，优先级更高）</li>
     *   <li>网关自身的 {@code MerchantRepository}（生产路径：merchant 表由网关承载，
     *       fundreport 包直接读仓储不构成循环依赖）</li>
     * </ol>
     *
     * <p>此前只有第 1 条且生产**没有任何 Bean 提供它**，于是「户名必须与商户主体名一致」
     * 这条二清护栏**恒被跳过**（与类注释自相矛盾）。现在生产会真正取到商户名并比对；
     * 只有两者都不可用（如纯单测未设置解析器）时才跳过。</p>
     */
    private String resolveMerchantName(Long merchantId) {
        if (merchantNameResolver != null) {
            return merchantNameResolver.apply(merchantId);
        }
        if (merchantRepository != null && merchantId != null) {
            return merchantRepository.findById(merchantId)
                    .map(org.nexus.gateway.model.Merchant::getMerchantName)
                    .orElse(null);
        }
        return null;
    }

    /** 商户主体名解析器（由配置类或测试注入；与 repository 二选一，优先级更高）。 */
    private java.util.function.Function<Long, String> merchantNameResolver;

    /** 生产路径的商户名来源（网关自身的 merchant 表）。 */
    private org.nexus.gateway.repository.MerchantRepository merchantRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMerchantNameResolver(java.util.function.Function<Long, String> resolver) {
        this.merchantNameResolver = resolver;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMerchantRepository(
            org.nexus.gateway.repository.MerchantRepository merchantRepository) {
        this.merchantRepository = merchantRepository;
    }
}
