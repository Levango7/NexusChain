package org.nexus.gateway.fundreport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 出款去向模型门禁测试（P0 修复 2026-10-11）。
 *
 * <p>重点钉住三条护栏：<b>二清红线</b>（非持牌主体不得绑平台结算账户）、
 * <b>户名一致性</b>、<b>没有生效账户就 fail-closed</b>（不出款）。</p>
 */
class MerchantPayoutAccountServiceTest {

    private MerchantPayoutAccountRepository repository;
    private MerchantPayoutAccountService service;

    private static final Long MERCHANT_ID = 700L;

    @BeforeEach
    void setUp() {
        repository = mock(MerchantPayoutAccountRepository.class);
        service = new MerchantPayoutAccountService(repository);
        service.setMerchantNameResolver(id -> "商户口径公司");
    }

    private MerchantPayoutAccount savedAccount(MerchantPayoutAccount.AccountStatus status) {
        MerchantPayoutAccount a = new MerchantPayoutAccount();
        a.setId(1L);
        a.setMerchantId(MERCHANT_ID);
        a.setAccountType(MerchantPayoutAccount.AccountType.BANK_CARD);
        a.setAccountNo("6222****8888");
        a.setAccountName("商户口径公司");
        a.setStatus(status);
        return a;
    }

    @Test
    @DisplayName("绑定：正常商户账户 → 进入 PENDING_VERIFY（需核验后才可用）")
    void bind_pendingVerify() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MerchantPayoutAccount a = service.bind(MERCHANT_ID,
                MerchantPayoutAccount.AccountType.BANK_CARD, "6222****8888", "商户口径公司", false);

        assertEquals(MerchantPayoutAccount.AccountStatus.PENDING_VERIFY, a.getStatus());
        assertFalse(a.isActive());
    }

    @Test
    @DisplayName("二清红线：非持牌主体绑平台结算账户 → 拒绝")
    void bind_platformSettlementWithOutCompliance_rejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                service.bind(MERCHANT_ID, MerchantPayoutAccount.AccountType.PLATFORM_SETTLEMENT,
                        "ACC-1", "商户口径公司", false));
        assertTrue(ex.getMessage().contains("二清"), ex.getMessage());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("二清红线：持牌主体可绑平台结算账户（complianceEntity=true）")
    void bind_platformSettlementWithCompliance_allowed() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MerchantPayoutAccount a = service.bind(MERCHANT_ID,
                MerchantPayoutAccount.AccountType.PLATFORM_SETTLEMENT, "ACC-1", "商户口径公司", true);

        assertTrue(a.isComplianceEntity());
        assertEquals(MerchantPayoutAccount.AccountStatus.PENDING_VERIFY, a.getStatus());
    }

    @Test
    @DisplayName("户名一致性：户名与商户主体不一致 → 拒绝绑定")
    void bind_nameMismatch_rejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                service.bind(MERCHANT_ID, MerchantPayoutAccount.AccountType.BANK_CARD,
                        "6222****8888", "别人的名字", false));
        assertTrue(ex.getMessage().contains("户名"), ex.getMessage());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("resolveActiveAccount：没有生效账户 → empty（调用方必须 fail-closed 不出款）")
    void resolveActive_none_empty() {
        when(repository.findByMerchantIdAndStatus(eq(MERCHANT_ID),
                eq(MerchantPayoutAccount.AccountStatus.ACTIVE))).thenReturn(Optional.empty());

        Optional<MerchantPayoutAccount> resolved = service.resolveActiveAccount(MERCHANT_ID);

        assertTrue(resolved.isEmpty());
    }

    @Test
    @DisplayName("activate / disable 状态机")
    void activateAndDisable() {
        when(repository.findById(1L)).thenReturn(Optional.of(savedAccount(
                MerchantPayoutAccount.AccountStatus.PENDING_VERIFY)));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MerchantPayoutAccount activated = service.activate(1L);
        assertTrue(activated.isActive(), "核验后应生效");

        MerchantPayoutAccount disabled = service.disable(1L);
        assertFalse(disabled.isActive(), "停用后不可再作出款去向");
    }

    /**
     * 户名一致性护栏的**生产路径**（2026-10-12 回归门禁）。
     *
     * <p>此前该护栏只在测试手工调用 {@code setMerchantNameResolver} 时才生效，生产
     * 没有任何 Bean 提供解析器 ⇒ 校验恒被跳过（与类注释「户名必须一致」矛盾）。
     * 现在生产走 {@code MerchantRepository} 取商户名，本用例钉住「无解析器也能拦住户名不符」。</p>
     */
    @Test
    @DisplayName("户名一致性（生产路径）：仅注入 MerchantRepository 时，户名不符仍被拒绝")
    void bind_usesMerchantRepository_rejectsMismatch() {
        org.nexus.gateway.repository.MerchantRepository merchantRepository =
                mock(org.nexus.gateway.repository.MerchantRepository.class);
        org.nexus.gateway.model.Merchant merchant = new org.nexus.gateway.model.Merchant();
        merchant.setMerchantName("商户口径公司");
        when(merchantRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(merchant));

        // 只注入仓储、**不**注入 resolver —— 模拟生产装配
        MerchantPayoutAccountService prodPathService =
                new MerchantPayoutAccountService(repository);
        prodPathService.setMerchantRepository(merchantRepository);

        assertThrows(IllegalArgumentException.class, () -> prodPathService.bind(MERCHANT_ID,
                MerchantPayoutAccount.AccountType.BANK_CARD, "6222****8888", "别人的账户名", false));

        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        MerchantPayoutAccount ok = prodPathService.bind(MERCHANT_ID,
                MerchantPayoutAccount.AccountType.BANK_CARD, "6222****8888", "商户口径公司", false);
        assertEquals(MerchantPayoutAccount.AccountStatus.PENDING_VERIFY, ok.getStatus());
        verify(merchantRepository, atLeastOnce()).findById(MERCHANT_ID);
    }
}

/**
 * 人工打款执行器门禁测试。
 */
class ManualPayoutExecutorTest {

    @Test
    @DisplayName("人工执行器：登记待打款任务，且同一 reference 幂等（不重复登记）")
    void manualPayout_idempotent() {
        ManualPayoutExecutor executor = new ManualPayoutExecutor();
        MerchantPayoutAccount account = new MerchantPayoutAccount();
        account.setStatus(MerchantPayoutAccount.AccountStatus.ACTIVE);

        String t1 = executor.pay(700L, account, new BigDecimal("100.00"), "REF1");
        String t2 = executor.pay(700L, account, new BigDecimal("100.00"), "REF1");

        assertEquals(t1, t2, "同一 reference 必须幂等（否则会重复打款）");
        assertTrue(t1.startsWith("MANUAL_PAYOUT_REF1"));
    }

    @Test
    @DisplayName("人工执行器：账户未生效 → fail-closed 抛错（不出款）")
    void manualPayout_inactiveAccount_failsClosed() {
        ManualPayoutExecutor executor = new ManualPayoutExecutor();
        MerchantPayoutAccount account = new MerchantPayoutAccount();
        account.setStatus(MerchantPayoutAccount.AccountStatus.PENDING_VERIFY);

        assertThrows(PayoutException.class, () ->
                executor.pay(700L, account, new BigDecimal("100.00"), "REF2"));
    }
}
