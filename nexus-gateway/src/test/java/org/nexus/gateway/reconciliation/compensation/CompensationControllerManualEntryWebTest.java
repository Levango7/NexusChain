package org.nexus.gateway.reconciliation.compensation;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.gateway.security.MerchantOwnershipGuard;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 补偿人工入口端点 Web 层测试（2026-10-12 新增）。
 *
 * <p>钉住三件事：入口存在；**未创建时返回 422 而不是假装成功**；
 * 归属校验先于落库（差错不存在时不得调用创建）。</p>
 */
@ExtendWith(MockitoExtension.class)
class CompensationControllerManualEntryWebTest {

    @Mock
    private AutoCompensationService service;

    @Mock
    private MerchantOwnershipGuard ownershipGuard;

    private MockMvc mockMvc;

    private static final String URL = "/api/v1/reconciliation/compensations/discrepancy/5/compensate";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new CompensationController(service, ownershipGuard))
                .setControllerAdvice(new org.nexus.gateway.config.GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("人工入口 — 创建成功返回 200")
    void createsCompensation() throws Exception {
        when(ownershipGuard.requireMerchantId(any(HttpServletRequest.class))).thenReturn(700L);
        when(service.merchantIdOfDiscrepancy(5L)).thenReturn(Optional.of(700L));
        CompensationRecord created = new CompensationRecord();
        created.setId(9L);
        when(service.createCompensationForDiscrepancyId(5L)).thenReturn(Optional.of(created));

        mockMvc.perform(post(URL)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("人工入口 — 未创建（非长款/已有补偿/超限）返回 422")
    void returns422WhenNotCreated() throws Exception {
        when(ownershipGuard.requireMerchantId(any(HttpServletRequest.class))).thenReturn(700L);
        when(service.merchantIdOfDiscrepancy(5L)).thenReturn(Optional.of(700L));
        when(service.createCompensationForDiscrepancyId(5L)).thenReturn(Optional.empty());

        mockMvc.perform(post(URL)).andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("人工入口 — 差错不存在返回 404 且不调用创建")
    void returns404AndDoesNotCreate() throws Exception {
        when(ownershipGuard.requireMerchantId(any(HttpServletRequest.class))).thenReturn(700L);
        when(service.merchantIdOfDiscrepancy(5L)).thenReturn(Optional.empty());

        mockMvc.perform(post(URL)).andExpect(status().isNotFound());

        verify(service, never()).createCompensationForDiscrepancyId(any());
    }
}
