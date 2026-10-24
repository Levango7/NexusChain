package org.nexus.gateway.fundreport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.gateway.account.AccountController;
import org.nexus.gateway.account.AccountService;
import org.nexus.gateway.account.AccountTransactionRepository;
import org.nexus.gateway.account.MerchantAccount;
import org.nexus.gateway.config.GlobalExceptionHandler;
import org.nexus.gateway.security.MerchantOwnershipGuard;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商户资金类 API 的跨商户越权防护（2026-10-25）。
 *
 * <p><b>背景</b>：这三个控制器的商户端点此前只要求
 * {@code hasRole('MERCHANT') or hasRole('ADMIN')}，然后把路径 {@code merchantId}
 * 直接交给 Service —— <b>没有任何归属校验</b>。用本类的前身探针实测：
 * 调用方商户 200 请求 {@code GET /api/v1/fund-dashboard/999} 与
 * {@code GET /api/v1/accounts/999} 都返回 <b>200 + 商户 999 的数据</b>（期望 403）。
 * {@code POST /{merchantId}/withdraw} 与 {@code POST /{merchantId}/configure}
 * 同型——即"改个路径参数就能从他人账户提现 / 替他人配自动出款"。</p>
 *
 * <p><b>本类用 standalone MockMvc + 真实 {@link MerchantOwnershipGuard} +
 * {@link GlobalExceptionHandler}</b>，通过 {@code requestAttr} 模拟
 * {@code ApiKeyInterceptor} 注入的 {@code nexus.merchantId}。</p>
 *
 * <p><b>刻意不经过 {@code @PreAuthorize}</b>：standalone 不装配方法安全代理，
 * 所以本类只检验"归属校验在不在、顺序对不对"，不检验角色门。
 * 角色门（API Key 调用方没有 Authentication，{@code hasRole('MERCHANT')} 恒假）
 * 是另一个独立问题，已在各控制器 javadoc 里写明。</p>
 */
@ExtendWith(MockitoExtension.class)
class MerchantFundApiOwnershipTest {

    /** ApiKeyInterceptor 注入的认证商户上下文。 */
    private static final String MERCHANT_ATTR = MerchantOwnershipGuard.MERCHANT_ID_ATTR;
    private static final long CALLER = 200L;
    private static final long OTHER = 999L;

    @Mock private FundDashboardService fundDashboardService;
    @Mock private AccountService accountService;
    @Mock private AccountTransactionRepository transactionRepository;
    @Mock private AutoWithdrawService autoWithdrawService;

    // === MockMvc 装配 ===

    private MockMvc fundDashboardMvc() {
        return MockMvcBuilders
                .standaloneSetup(new FundDashboardController(fundDashboardService,
                        new MerchantOwnershipGuard()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private MockMvc accountMvc() {
        return MockMvcBuilders
                .standaloneSetup(new AccountController(accountService, transactionRepository,
                        new MerchantOwnershipGuard()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private MockMvc autoWithdrawMvc() {
        return MockMvcBuilders
                .standaloneSetup(new AutoWithdrawController(autoWithdrawService,
                        new MerchantOwnershipGuard()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // === 资金看板 ===

    @Test
    @DisplayName("当前商户看板（路径不带 id）：从认证上下文取 merchantId 并返回 200")
    void myDashboard_usesContextMerchantId() throws Exception {
        when(fundDashboardService.getDashboard(CALLER)).thenReturn(Map.of("merchantId", CALLER));

        fundDashboardMvc()
                .perform(get("/api/v1/fund-dashboard").requestAttr(MERCHANT_ATTR, CALLER))
                .andExpect(status().isOk());

        // 关键：用的是上下文里的 merchantId，不是任何客户端可伪造的值
        verify(fundDashboardService).getDashboard(eq(CALLER));
    }

    @Test
    @DisplayName("当前商户看板：缺少商户上下文时 fail-closed（403）")
    void myDashboard_missingContextForbidden() throws Exception {
        fundDashboardMvc()
                .perform(get("/api/v1/fund-dashboard"))
                .andExpect(status().isForbidden());

        verify(fundDashboardService, never()).getDashboard(anyLong());
    }

    @Test
    @DisplayName("资金看板：跨商户读取 403（修复前实测 200）")
    void dashboard_crossMerchantForbidden() throws Exception {
        lenient().when(fundDashboardService.getDashboard(anyLong()))
                .thenReturn(Map.of("merchantId", OTHER));

        fundDashboardMvc()
                .perform(get("/api/v1/fund-dashboard/{id}", OTHER).requestAttr(MERCHANT_ATTR, CALLER))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("资金看板：读自己的 200")
    void dashboard_ownMerchantAllowed() throws Exception {
        when(fundDashboardService.getDashboard(CALLER)).thenReturn(Map.of("merchantId", CALLER));

        fundDashboardMvc()
                .perform(get("/api/v1/fund-dashboard/{id}", CALLER).requestAttr(MERCHANT_ATTR, CALLER))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("资金看板：缺少商户上下文时 fail-closed（403，不是放行）")
    void dashboard_missingContextForbidden() throws Exception {
        fundDashboardMvc()
                .perform(get("/api/v1/fund-dashboard/{id}", CALLER))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("资金看板：overview / trend / recent / anomaly 四个子端点同样禁止跨商户")
    void dashboard_subEndpoints_crossMerchantForbidden() throws Exception {
        MockMvc mvc = fundDashboardMvc();
        for (String suffix : new String[] {"/overview", "/trend", "/recent", "/anomaly"}) {
            mvc.perform(get("/api/v1/fund-dashboard/{id}" + suffix, OTHER)
                            .requestAttr(MERCHANT_ATTR, CALLER))
                    .andExpect(status().isForbidden());
        }
    }

    // === 账户 ===

    @Test
    @DisplayName("账户余额：跨商户读取 403（修复前实测 200）")
    void balance_crossMerchantForbidden() throws Exception {
        lenient().when(accountService.getBalance(anyLong())).thenReturn(Map.of("balance", "0"));

        accountMvc()
                .perform(get("/api/v1/accounts/{id}", OTHER).requestAttr(MERCHANT_ATTR, CALLER))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("账户余额：读自己的 200")
    void balance_ownMerchantAllowed() throws Exception {
        when(accountService.getBalance(CALLER)).thenReturn(Map.of("balance", "100"));

        accountMvc()
                .perform(get("/api/v1/accounts/{id}", CALLER).requestAttr(MERCHANT_ATTR, CALLER))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("账户流水：跨商户读取 403")
    void transactions_crossMerchantForbidden() throws Exception {
        accountMvc()
                .perform(get("/api/v1/accounts/{id}/transactions", OTHER)
                        .requestAttr(MERCHANT_ATTR, CALLER))
                .andExpect(status().isForbidden());
    }

    /**
     * 本类最重要的用例：越权提现必须<b>既返回 403、又没有真的动钱</b>。
     *
     * <p>只断言 403 不够——如果守卫放在 {@code accountService.withdraw(...)} <b>之后</b>，
     * 状态码同样是 403，但钱已经出去了。故必须同时 verify 服务未被调用。</p>
     */
    @Test
    @DisplayName("提现：跨商户 403 且**不调用** withdraw（钱不能动）")
    void withdraw_crossMerchantForbiddenAndNoMoneyMoved() throws Exception {
        accountMvc()
                .perform(post("/api/v1/accounts/{id}/withdraw", OTHER)
                        .requestAttr(MERCHANT_ATTR, CALLER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"100\",\"orderId\":\"O-1\"}"))
                .andExpect(status().isForbidden());

        verify(accountService, never()).withdraw(anyLong(), any());
    }

    @Test
    @DisplayName("提现：提自己的会真的调用 withdraw")
    void withdraw_ownMerchantCallsService() throws Exception {
        MerchantAccount account = new MerchantAccount();
        account.setMerchantId(CALLER);
        account.setBalance(new BigDecimal("900"));
        when(accountService.withdraw(eq(CALLER), any())).thenReturn(account);

        accountMvc()
                .perform(post("/api/v1/accounts/{id}/withdraw", CALLER)
                        .requestAttr(MERCHANT_ATTR, CALLER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"100\",\"orderId\":\"O-2\"}"))
                .andExpect(status().isOk());

        verify(accountService).withdraw(eq(CALLER), any());
    }

    // === 自动提现 ===

    @Test
    @DisplayName("自动提现：跨商户配置 403 且**不调用** configure（不能替他人配自动出款）")
    void autoWithdrawConfigure_crossMerchantForbiddenAndNotConfigured() throws Exception {
        autoWithdrawMvc()
                .perform(post("/api/v1/auto-withdraw/{id}/configure", OTHER)
                        .requestAttr(MERCHANT_ATTR, CALLER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"frequency\":\"DAILY\",\"threshold\":\"1000\"}"))
                .andExpect(status().isForbidden());

        verify(autoWithdrawService, never()).configureAutoWithdraw(anyLong(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("自动提现：跨商户查询规则 403")
    void autoWithdrawRules_crossMerchantForbidden() throws Exception {
        lenient().when(autoWithdrawService.getAutoWithdrawRule(anyLong())).thenReturn(List.of());

        autoWithdrawMvc()
                .perform(get("/api/v1/auto-withdraw/{id}", OTHER).requestAttr(MERCHANT_ATTR, CALLER))
                .andExpect(status().isForbidden());
    }
}
