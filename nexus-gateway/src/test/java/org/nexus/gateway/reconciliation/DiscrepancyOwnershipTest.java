package org.nexus.gateway.reconciliation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.gateway.config.GlobalExceptionHandler;
import org.nexus.gateway.security.MerchantOwnershipGuard;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 差错处理 API 的归属校验（2026-10-25，第三批）。
 *
 * <p>这是三批里<b>风险最高</b>的一批：商户可访问的 6 个端点中，4 个是
 * "路径只有 {@code discrepancyId}" 的资源号形态，其中 3 个是<b>写操作</b>
 * （{@code investigate} / {@code resolve} / {@code escalate}）——
 * 修复前任何商户换一个 ID 就能推进他人的差错状态。</p>
 *
 * <p>还有一处<b>混合形态</b>：{@code GET /{merchantId}/file/{fileId}} 校验了路径
 * {@code merchantId}，却没校验 {@code fileId} ⇒ "自己的 merchantId + 别人的 fileId"
 * 就能读他人差错。本类用 3 个用例钉住它（跨 merchantId 403 / 跨 fileId 403 / 自己的 200）。</p>
 *
 * <p><b>写端点一律做"403 且服务未被调用"的成对断言</b>：只断言状态码不够——
 * 守卫若放在 Service 调用之后，状态码同样是 403，但差错已经被推进了。</p>
 */
@ExtendWith(MockitoExtension.class)
class DiscrepancyOwnershipTest {

    private static final String MERCHANT_ATTR = MerchantOwnershipGuard.MERCHANT_ID_ATTR;
    private static final long CALLER = 200L;
    private static final long OTHER = 999L;

    @Mock private DiscrepancyResolutionService resolutionService;

    private MockMvc mvc() {
        return MockMvcBuilders
                .standaloneSetup(new DiscrepancyResolutionController(resolutionService,
                        new MerchantOwnershipGuard()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static ReconciliationDiscrepancy discrepancy(long id, Long ownerMerchantId) {
        ReconciliationDiscrepancy d = new ReconciliationDiscrepancy();
        d.setId(id);
        d.setMerchantId(ownerMerchantId);
        return d;
    }

    // === 列表：路径 merchantId 形态 ===

    @Test
    @DisplayName("差错列表：跨商户 403")
    void list_crossMerchantForbidden() throws Exception {
        lenient().when(resolutionService.getDiscrepanciesByMerchant(anyLong()))
                .thenReturn(List.of());

        mvc().perform(get("/api/v1/reconciliation/discrepancies/{id}", OTHER)
                        .requestAttr(MERCHANT_ATTR, CALLER))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("差错列表：读自己的 200")
    void list_ownMerchantAllowed() throws Exception {
        when(resolutionService.getDiscrepanciesByMerchant(CALLER)).thenReturn(List.of());

        mvc().perform(get("/api/v1/reconciliation/discrepancies/{id}", CALLER)
                        .requestAttr(MERCHANT_ATTR, CALLER))
                .andExpect(status().isOk());
    }

    // === 按对账文件查：混合形态 ===

    @Test
    @DisplayName("按文件查：路径 merchantId 是别人的 → 403")
    void byFile_crossMerchantIdForbidden() throws Exception {
        mvc().perform(get("/api/v1/reconciliation/discrepancies/{id}/file/{fid}", OTHER, 7L)
                        .requestAttr(MERCHANT_ATTR, CALLER))
                .andExpect(status().isForbidden());

        verify(resolutionService, never()).getDiscrepanciesByFileId(anyLong());
    }

    @Test
    @DisplayName("按文件查：自己的 merchantId + 别人的 fileId → 403（fileId 也必须校验）")
    void byFile_otherMerchantsFileForbidden() throws Exception {
        when(resolutionService.getDiscrepanciesByFileId(7L))
                .thenReturn(List.of(discrepancy(1L, OTHER)));

        mvc().perform(get("/api/v1/reconciliation/discrepancies/{id}/file/{fid}", CALLER, 7L)
                        .requestAttr(MERCHANT_ATTR, CALLER))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("按文件查：自己的 merchantId + 自己的 fileId → 200")
    void byFile_ownFileAllowed() throws Exception {
        when(resolutionService.getDiscrepanciesByFileId(7L))
                .thenReturn(List.of(discrepancy(1L, CALLER)));

        mvc().perform(get("/api/v1/reconciliation/discrepancies/{id}/file/{fid}", CALLER, 7L)
                        .requestAttr(MERCHANT_ATTR, CALLER))
                .andExpect(status().isOk());
    }

    // === 详情：资源号形态 ===

    @Test
    @DisplayName("差错详情：他人的差错 ID → 403")
    void detail_crossMerchantForbidden() throws Exception {
        when(resolutionService.findDiscrepancyById(1L))
                .thenReturn(Optional.of(discrepancy(1L, OTHER)));

        mvc().perform(get("/api/v1/reconciliation/discrepancies/detail/{id}", 1L)
                        .requestAttr(MERCHANT_ATTR, CALLER))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("差错详情：自己的 → 200")
    void detail_ownAllowed() throws Exception {
        when(resolutionService.findDiscrepancyById(1L))
                .thenReturn(Optional.of(discrepancy(1L, CALLER)));

        mvc().perform(get("/api/v1/reconciliation/discrepancies/detail/{id}", 1L)
                        .requestAttr(MERCHANT_ATTR, CALLER))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("差错详情：不存在仍 404（不要为了藏存在性改成 403）")
    void detail_notFoundStaysNotFound() throws Exception {
        when(resolutionService.findDiscrepancyById(anyLong())).thenReturn(Optional.empty());

        mvc().perform(get("/api/v1/reconciliation/discrepancies/detail/{id}", 1L)
                        .requestAttr(MERCHANT_ATTR, CALLER))
                .andExpect(status().isNotFound());
    }

    // === 三个写端点 ===

    @Test
    @DisplayName("调查：他人的差错 403 且**不推进状态**")
    void investigate_crossMerchantForbiddenAndNotAdvanced() throws Exception {
        when(resolutionService.findDiscrepancyById(1L))
                .thenReturn(Optional.of(discrepancy(1L, OTHER)));

        mvc().perform(post("/api/v1/reconciliation/discrepancies/{id}/investigate", 1L)
                        .requestAttr(MERCHANT_ATTR, CALLER))
                .andExpect(status().isForbidden());

        verify(resolutionService, never()).startInvestigation(anyLong());
    }

    @Test
    @DisplayName("调查：自己的会真的推进")
    void investigate_ownCallsService() throws Exception {
        when(resolutionService.findDiscrepancyById(1L))
                .thenReturn(Optional.of(discrepancy(1L, CALLER)));
        when(resolutionService.startInvestigation(1L)).thenReturn(discrepancy(1L, CALLER));

        mvc().perform(post("/api/v1/reconciliation/discrepancies/{id}/investigate", 1L)
                        .requestAttr(MERCHANT_ATTR, CALLER))
                .andExpect(status().isOk());

        verify(resolutionService).startInvestigation(eq(1L));
    }

    @Test
    @DisplayName("解决：他人的差错 403 且**不解决**")
    void resolve_crossMerchantForbiddenAndNotResolved() throws Exception {
        when(resolutionService.findDiscrepancyById(2L))
                .thenReturn(Optional.of(discrepancy(2L, OTHER)));

        mvc().perform(post("/api/v1/reconciliation/discrepancies/{id}/resolve", 2L)
                        .param("resolutionNote", "x")
                        .requestAttr(MERCHANT_ATTR, CALLER))
                .andExpect(status().isForbidden());

        verify(resolutionService, never()).resolveDiscrepancy(anyLong(), any());
    }

    @Test
    @DisplayName("升级：他人的差错 403 且**不升级**")
    void escalate_crossMerchantForbiddenAndNotEscalated() throws Exception {
        when(resolutionService.findDiscrepancyById(3L))
                .thenReturn(Optional.of(discrepancy(3L, OTHER)));

        mvc().perform(post("/api/v1/reconciliation/discrepancies/{id}/escalate", 3L)
                        .param("escalationNote", "x")
                        .requestAttr(MERCHANT_ATTR, CALLER))
                .andExpect(status().isForbidden());

        verify(resolutionService, never()).escalateDiscrepancy(anyLong(), any());
    }

    @Test
    @DisplayName("升级：不存在的差错仍 404")
    void escalate_notFoundStaysNotFound() throws Exception {
        when(resolutionService.findDiscrepancyById(anyLong())).thenReturn(Optional.empty());

        mvc().perform(post("/api/v1/reconciliation/discrepancies/{id}/escalate", 3L)
                        .param("escalationNote", "x")
                        .requestAttr(MERCHANT_ATTR, CALLER))
                .andExpect(status().isNotFound());

        verify(resolutionService, never()).escalateDiscrepancy(anyLong(), anyString());
    }
}
