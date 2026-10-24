package org.nexus.gateway.fundreport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.gateway.config.GlobalExceptionHandler;
import org.nexus.gateway.reserve.ReserveFundController;
import org.nexus.gateway.reserve.ReserveFundService;
import org.nexus.gateway.security.MerchantOwnershipGuard;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
 * 商户资金类 API 归属校验 · 第二批（2026-10-25）。
 *
 * <p>覆盖 {@code FundReportController} 与 {@code ReserveFundController}。
 * 与第一批（{@code MerchantFundApiOwnershipTest}）同法：standalone MockMvc +
 * 真实 {@link MerchantOwnershipGuard} + {@link GlobalExceptionHandler}，
 * 用 {@code requestAttr} 模拟 {@code ApiKeyInterceptor} 注入的商户上下文。</p>
 *
 * <h3>本批新增的一类缺陷：按资源号查</h3>
 *
 * <p>{@code GET /api/v1/fund-reports/detail/{reportNo}} <b>路径里没有 merchantId</b>，
 * 修复前只按报表号取出来就整份返回（响应体还带着 {@code report.getMerchantId()}）。
 * 路径参数守卫管不到这种形态，必须<b>先加载资源、再比对它属于谁</b>。
 * 本类用 3 个用例钉住这个模式：他人报表 403 / 自己的 200 / 不存在 404。</p>
 */
@ExtendWith(MockitoExtension.class)
class FundReportAndReserveOwnershipTest {

    private static final String MERCHANT_ATTR = MerchantOwnershipGuard.MERCHANT_ID_ATTR;
    private static final long CALLER = 200L;
    private static final long OTHER = 999L;

    @Mock private FundReportService fundReportService;
    @Mock private ReserveFundService reserveFundService;

    private MockMvc reportMvc() {
        return MockMvcBuilders
                .standaloneSetup(new FundReportController(fundReportService,
                        new MerchantOwnershipGuard()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private MockMvc reserveMvc() {
        return MockMvcBuilders
                .standaloneSetup(new ReserveFundController(reserveFundService,
                        new MerchantOwnershipGuard()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static FundReport report(Long ownerMerchantId) {
        FundReport r = new FundReport();
        r.setReportNo("R-1");
        r.setMerchantId(ownerMerchantId);
        r.setReportType(ReportType.DAILY);
        r.setReportFormat(ReportFormat.CSV);
        return r;
    }

    // === 资金报表：路径 merchantId 形态 ===

    @Test
    @DisplayName("报表生成：跨商户 403 且不调用 generateReport")
    void generate_crossMerchantForbiddenAndNotGenerated() throws Exception {
        reportMvc()
                .perform(post("/api/v1/fund-reports/{id}/generate", OTHER)
                        .requestAttr(MERCHANT_ATTR, CALLER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportType\":\"DAILY\",\"reportFormat\":\"CSV\"}"))
                .andExpect(status().isForbidden());

        verify(fundReportService, never()).generateReport(anyLong(), any(), any());
    }

    @Test
    @DisplayName("报表列表：跨商户 403")
    void list_crossMerchantForbidden() throws Exception {
        lenient().when(fundReportService.getReports(anyLong(), anyInt(), anyInt()))
                .thenReturn(Page.empty());

        reportMvc()
                .perform(get("/api/v1/fund-reports/{id}", OTHER).requestAttr(MERCHANT_ATTR, CALLER))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("报表列表：读自己的 200")
    void list_ownMerchantAllowed() throws Exception {
        when(fundReportService.getReports(eq(CALLER), anyInt(), anyInt())).thenReturn(Page.empty());

        reportMvc()
                .perform(get("/api/v1/fund-reports/{id}", CALLER).requestAttr(MERCHANT_ATTR, CALLER))
                .andExpect(status().isOk());
    }

    // === 资金报表：资源号形态（本批新增的模式）===

    @Test
    @DisplayName("报表详情：他人报表号 403（路径无 merchantId，靠加载后比对）")
    void detail_otherMerchantReportForbidden() throws Exception {
        when(fundReportService.getReportByNo("R-OTHER")).thenReturn(Optional.of(report(OTHER)));

        reportMvc()
                .perform(get("/api/v1/fund-reports/detail/{no}", "R-OTHER")
                        .requestAttr(MERCHANT_ATTR, CALLER))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("报表详情：自己的报表 200")
    void detail_ownReportAllowed() throws Exception {
        when(fundReportService.getReportByNo("R-MINE")).thenReturn(Optional.of(report(CALLER)));

        reportMvc()
                .perform(get("/api/v1/fund-reports/detail/{no}", "R-MINE")
                        .requestAttr(MERCHANT_ATTR, CALLER))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("报表详情：报表不存在仍是 404（不要为了藏存在性把 404 变成 403）")
    void detail_notFoundStaysNotFound() throws Exception {
        when(fundReportService.getReportByNo(anyString())).thenReturn(Optional.empty());

        reportMvc()
                .perform(get("/api/v1/fund-reports/detail/{no}", "R-NONE")
                        .requestAttr(MERCHANT_ATTR, CALLER))
                .andExpect(status().isNotFound());
    }

    // === 备付金 ===

    @Test
    @DisplayName("备付金配置：跨商户 403")
    void reserveConfig_crossMerchantForbidden() throws Exception {
        lenient().when(reserveFundService.getConfig(anyLong())).thenReturn(Optional.empty());

        reserveMvc()
                .perform(get("/api/v1/reserve/configs/{id}", OTHER)
                        .requestAttr(MERCHANT_ATTR, CALLER))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("备付金余额：跨商户 403")
    void reserveBalance_crossMerchantForbidden() throws Exception {
        lenient().when(reserveFundService.getReserveBalance(anyLong()))
                .thenReturn(BigDecimal.TEN);

        reserveMvc()
                .perform(get("/api/v1/reserve/{id}/balance", OTHER)
                        .requestAttr(MERCHANT_ATTR, CALLER))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("备付金余额：读自己的 200")
    void reserveBalance_ownMerchantAllowed() throws Exception {
        when(reserveFundService.getReserveBalance(CALLER)).thenReturn(new BigDecimal("1000"));
        lenient().when(reserveFundService.getConfig(CALLER)).thenReturn(Optional.empty());

        reserveMvc()
                .perform(get("/api/v1/reserve/{id}/balance", CALLER)
                        .requestAttr(MERCHANT_ATTR, CALLER))
                .andExpect(status().isOk());
    }
}
