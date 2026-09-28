package org.nexus.gateway.reconciliation.bill;

import jakarta.servlet.http.HttpServletRequest;
import org.nexus.gateway.security.MerchantOwnershipGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 对账单下载 REST API。
 *
 * <p>提供对账单下载触发接口，支持按渠道类型和日期下载对账单。</p>
 *
 * <p>P0-2 修复：downloadBill 端点添加商户归属校验，防止 IDOR 攻击。</p>
 *
 * <p>接口列表：</p>
 * <ul>
 *   <li>{@code POST /api/v1/reconciliation/bills/download} — 下载指定渠道和日期的对账单</li>
 *   <li>{@code GET /api/v1/reconciliation/bills/dry-run/{channelType}} — 查询渠道是否为 dry-run 模式</li>
 * </ul>
 *
 * <p>2026-09-28 路径迁移：/api/reconciliation/bills → /api/v1/reconciliation/bills
 * （原路径不在 ApiKeyInterceptor 拦截范围，MerchantOwnershipGuard fail-closed 不可达）。</p>
 */
@RestController
@RequestMapping("/api/v1/reconciliation/bills")
public class BillDownloadController {

    private static final Logger log = LoggerFactory.getLogger(BillDownloadController.class);

    private final BillDownloadProxy billDownloadProxy;
    private final MerchantOwnershipGuard ownershipGuard;

    public BillDownloadController(BillDownloadProxy billDownloadProxy,
                                  MerchantOwnershipGuard ownershipGuard) {
        this.billDownloadProxy = billDownloadProxy;
        this.ownershipGuard = ownershipGuard;
    }

    /**
     * 下载对账单。
     *
     * <p>P0-2：请求体中的 merchantId 必须与认证上下文一致。</p>
     *
     * <p>请求体示例：
     * <pre>
     * {
     *   "channelType": "WECHAT",
     *   "merchantId": 1001,
     *   "billDate": "2026-09-27"
     * }
     * </pre>
     * </p>
     *
     * @param request     下载请求参数
     * @param httpRequest HTTP 请求（用于获取认证上下文）
     * @return 下载结果
     */
    @PostMapping("/download")
    public ResponseEntity<BillDownloadResult> downloadBill(@RequestBody BillDownloadRequest request,
                                                            HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        ownershipGuard.requireOwned(callerMerchantId, request.getMerchantId(), "bill-download", request.getMerchantId());

        log.info("[BillDownloadController] 收到下载请求: channelType={}, merchantId={}, billDate={}",
                request.getChannelType(), request.getMerchantId(), request.getBillDate());

        // 如果未指定 billDate，默认使用前一天
        String billDate = request.getBillDate();
        if (billDate == null || billDate.isBlank()) {
            billDate = LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
            log.info("[BillDownloadController] 未指定 billDate，使用前一天: {}", billDate);
        }

        BillDownloadResult result = billDownloadProxy.downloadBill(
                request.getChannelType(), request.getMerchantId(), billDate);

        if (result.isSuccess()) {
            return ResponseEntity.ok(result);
        } else {
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 查询渠道是否为 dry-run 模式。
     *
     * <p>纯渠道查询，无商户数据，不需要归属校验。</p>
     *
     * @param channelType 渠道类型
     * @return dry-run 状态
     */
    @GetMapping("/dry-run/{channelType}")
    public ResponseEntity<Map<String, Object>> checkDryRun(@PathVariable String channelType) {
        boolean dryRun = billDownloadProxy.isDryRun(channelType);
        return ResponseEntity.ok(Map.of(
                "channelType", channelType,
                "dryRun", dryRun
        ));
    }

    /**
     * 对账单下载请求 DTO。
     */
    public static class BillDownloadRequest {
        private String channelType;
        private Long merchantId;
        private String billDate;

        public String getChannelType() { return channelType; }
        public void setChannelType(String channelType) { this.channelType = channelType; }

        public Long getMerchantId() { return merchantId; }
        public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

        public String getBillDate() { return billDate; }
        public void setBillDate(String billDate) { this.billDate = billDate; }
    }
}