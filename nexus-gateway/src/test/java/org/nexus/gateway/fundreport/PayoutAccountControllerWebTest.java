package org.nexus.gateway.fundreport;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 出款账户管理端点 Web 层测试（P0 修复 2026-10-11）。
 *
 * <p>这些端点此前**根本不存在**：模型与闸门建好了，却没有入口 ⇒ 商户无法绑定出款账户
 * ⇒ 自动提现永远被闸门拒绝（功能等于没接上）。本测试钉住入口存在且护栏透传（二清红线）。</p>
 */
@ExtendWith(MockitoExtension.class)
class PayoutAccountControllerWebTest {

    @Mock
    private MerchantPayoutAccountService service;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new PayoutAccountController(service))
                .setControllerAdvice(new org.nexus.gateway.config.GlobalExceptionHandler())
                .build();
    }

    private MerchantPayoutAccount account(MerchantPayoutAccount.AccountStatus status) {
        MerchantPayoutAccount a = new MerchantPayoutAccount();
        a.setId(1L);
        a.setMerchantId(700L);
        a.setAccountType(MerchantPayoutAccount.AccountType.BANK_CARD);
        a.setAccountNo("6222****8888");
        a.setAccountName("商户口径公司");
        a.setStatus(status);
        return a;
    }

    @Test
    @DisplayName("POST /api/v1/payout-accounts → 绑定成功返回 PENDING_VERIFY")
    void bind_ok() throws Exception {
        when(service.bind(eq(700L), eq(MerchantPayoutAccount.AccountType.BANK_CARD),
                any(), any(), anyBoolean()))
                .thenReturn(account(MerchantPayoutAccount.AccountStatus.PENDING_VERIFY));

        mockMvc.perform(post("/api/v1/payout-accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "merchantId", 700,
                                "accountType", "bank_card",
                                "accountNo", "6222****8888",
                                "accountName", "商户口径公司"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_VERIFY"));
    }

    @Test
    @DisplayName("POST bind：违反合规护栏（二清红线等）→ 400 并原样透传原因")
    void bind_complianceViolation_400() throws Exception {
        when(service.bind(any(), any(), any(), any(), anyBoolean()))
                .thenThrow(new IllegalArgumentException(
                        "拒绝绑定平台结算账户：非持牌主体——平台方不得以自有账户收商户款并对外出款（二清红线）"));

        mockMvc.perform(post("/api/v1/payout-accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "merchantId", 700,
                                "accountType", "PLATFORM_SETTLEMENT",
                                "accountNo", "ACC-1",
                                "accountName", "商户口径公司"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("二清红线")));
    }

    @Test
    @DisplayName("GET /{merchantId} → 列出该商户全部账户（含待核验/停用）")
    void list_ok() throws Exception {
        when(service.list(700L)).thenReturn(List.of(
                account(MerchantPayoutAccount.AccountStatus.PENDING_VERIFY)));
        mockMvc.perform(get("/api/v1/payout-accounts/700"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].accountNo").value("6222****8888"));
    }

    @Test
    @DisplayName("POST /{id}/activate → 核验通过后生效")
    void activate_ok() throws Exception {
        when(service.activate(1L)).thenReturn(account(MerchantPayoutAccount.AccountStatus.ACTIVE));
        mockMvc.perform(post("/api/v1/payout-accounts/1/activate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("POST /{id}/disable → 停用")
    void disable_ok() throws Exception {
        when(service.disable(1L)).thenReturn(account(MerchantPayoutAccount.AccountStatus.DISABLED));
        mockMvc.perform(post("/api/v1/payout-accounts/1/disable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
    }
}
