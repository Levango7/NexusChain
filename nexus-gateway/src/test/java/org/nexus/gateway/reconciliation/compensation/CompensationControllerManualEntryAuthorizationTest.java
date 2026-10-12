package org.nexus.gateway.reconciliation.compensation;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.gateway.security.MerchantOwnershipException;
import org.nexus.gateway.security.MerchantOwnershipGuard;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 人工补偿入口的**授权**用例（补 PR #84 的缺口，2026-10-12）。
 *
 * <p>{@code CompensationControllerManualEntryWebTest} 里 {@code ownershipGuard}
 * 是空 mock —— {@code requireOwned} 默认什么都不做，于是那三个用例（200/422/404）
 * 全在"归属校验通过"的前提下跑。若哪天有人把 {@code requireOwned} 调用删掉或挪到
 * 创建之后，那三个用例照样全绿，而端点已变成任意商户可对别人的差错发起补偿。
 * 这里专门钉住"跨商户必须 403 且绝不动账"。</p>
 */
@ExtendWith(MockitoExtension.class)
class CompensationControllerManualEntryAuthorizationTest {

    @Mock private AutoCompensationService service;
    @Mock private MerchantOwnershipGuard ownershipGuard;

    private MockMvc mockMvc;

    private static final String URL =
            "/api/v1/reconciliation/compensations/discrepancy/5/compensate";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new CompensationController(service, ownershipGuard))
                .setControllerAdvice(new org.nexus.gateway.config.GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("跨商户发起补偿：403，且绝不创建补偿记录")
    void crossMerchantRequestIsRejectedWithoutCreating() throws Exception {
        // 调用方商户 700，差错归属商户 800
        when(ownershipGuard.requireMerchantId(any(HttpServletRequest.class))).thenReturn(700L);
        when(service.merchantIdOfDiscrepancy(5L)).thenReturn(Optional.of(800L));
        doThrow(new MerchantOwnershipException("not owned"))
                .when(ownershipGuard).requireOwned(eq(700L), eq(800L), eq("discrepancy"), eq(5L));

        mockMvc.perform(post(URL)).andExpect(status().isForbidden());

        verify(service, never()).createCompensationForDiscrepancyId(any());
    }

    @Test
    @DisplayName("归属校验必须发生在创建之前（顺序本身就是防线）")
    void ownershipCheckRunsBeforeCreation() throws Exception {
        when(ownershipGuard.requireMerchantId(any(HttpServletRequest.class))).thenReturn(700L);
        when(service.merchantIdOfDiscrepancy(5L)).thenReturn(Optional.of(700L));
        CompensationRecord created = new CompensationRecord();
        created.setId(9L);
        when(service.createCompensationForDiscrepancyId(5L)).thenReturn(Optional.of(created));

        mockMvc.perform(post(URL)).andExpect(status().isOk());

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(ownershipGuard, service);
        inOrder.verify(ownershipGuard).requireOwned(700L, 700L, "discrepancy", 5L);
        inOrder.verify(service).createCompensationForDiscrepancyId(5L);
    }

    @Test
    @DisplayName("调用方身份缺失：requireMerchantId 抛错时不得继续查归属或建单")
    void missingCallerIdentityStopsEarly() throws Exception {
        when(ownershipGuard.requireMerchantId(any(HttpServletRequest.class)))
                .thenThrow(new MerchantOwnershipException("no merchant context"));

        mockMvc.perform(post(URL)).andExpect(status().isForbidden());

        verify(service, never()).merchantIdOfDiscrepancy(any());
        verify(service, never()).createCompensationForDiscrepancyId(any());
    }
}
