package org.nexus.gateway.orchestration.routing.fallback;

import jakarta.servlet.http.HttpServletRequest;
import org.nexus.gateway.security.MerchantOwnershipGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * 跨渠道补偿路由 REST API（Wave 16 模块三）。
 *
 * <p>商户归属校验（P0 IDOR 防护沿用 Wave 15 模式）：路由请求中的 merchantId
 * 必须 = 认证上下文商户；按 paymentId 查询时逐条校验记录归属。</p>
 *
 * <p>接口列表：</p>
 * <ul>
 *   <li>{@code POST /api/v1/routing/compensation-routes/route} — 路由决策（幂等）</li>
 *   <li>{@code POST /api/v1/routing/compensation-routes/{compensationId}/complete} — 回填结果</li>
 *   <li>{@code GET /api/v1/routing/compensation-routes/{compensationId}} — 单条查询（归属校验）</li>
 *   <li>{@code GET /api/v1/routing/compensation-routes/payment/{paymentId}} — 按支付查询（逐条归属校验）</li>
 *   <li>{@code GET /api/v1/routing/compensation-routes/merchant/{merchantId}} — 按商户查询（归属校验）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/routing/compensation-routes")
public class CompensationRoutingController {

    private static final Logger log = LoggerFactory.getLogger(CompensationRoutingController.class);

    private final CompensationRoutingService service;
    private final MerchantOwnershipGuard ownershipGuard;

    public CompensationRoutingController(CompensationRoutingService service,
                                         MerchantOwnershipGuard ownershipGuard) {
        this.service = service;
        this.ownershipGuard = ownershipGuard;
    }

    @PostMapping("/route")
    public ResponseEntity<CompensationRoutingRecord> route(@RequestBody RouteRequest request,
                                                           HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        ownershipGuard.requireOwned(callerMerchantId, request.getMerchantId(), "compensation-route", request.getMerchantId());
        try {
            return ResponseEntity.ok(service.route(request.getMerchantId(), request.getPaymentId(),
                    request.getCompensationId(), request.getOriginalConnector(),
                    request.getAmount(), request.getCurrency()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/{compensationId}/complete")
    public ResponseEntity<CompensationRoutingRecord> complete(@PathVariable String compensationId,
                                                              @RequestBody CompleteRequest request,
                                                              HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        CompensationRoutingRecord existing = service.getByCompensationId(compensationId).orElse(null);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        ownershipGuard.requireOwned(callerMerchantId, existing.getMerchantId(),
                "compensation-route", existing.getId());
        try {
            CompensationRoutingRecord.RouteResult result =
                    CompensationRoutingRecord.RouteResult.valueOf(request.getResult());
            if (result == CompensationRoutingRecord.RouteResult.PENDING
                    || result == CompensationRoutingRecord.RouteResult.SKIPPED_IDEMPOTENT) {
                return ResponseEntity.badRequest().build();
            }
            return ResponseEntity.ok(service.complete(compensationId, result,
                    request.getLatencyMs(), request.getErrorMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/{compensationId}")
    public ResponseEntity<CompensationRoutingRecord> get(@PathVariable String compensationId,
                                                         HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        CompensationRoutingRecord record = service.getByCompensationId(compensationId).orElse(null);
        if (record == null) {
            return ResponseEntity.notFound().build();
        }
        ownershipGuard.requireOwned(callerMerchantId, record.getMerchantId(), "compensation-route", record.getId());
        return ResponseEntity.ok(record);
    }

    @GetMapping("/payment/{paymentId}")
    public ResponseEntity<List<CompensationRoutingRecord>> listByPayment(@PathVariable String paymentId,
                                                                         HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        List<CompensationRoutingRecord> records = service.listByPayment(paymentId);
        for (CompensationRoutingRecord record : records) {
            // 逐条归属校验：任何不属于调用方的记录 → 整体 404（不泄露存在性）
            ownershipGuard.requireOwned(callerMerchantId, record.getMerchantId(), "compensation-route", record.getId());
        }
        return ResponseEntity.ok(records);
    }

    @GetMapping("/merchant/{merchantId}")
    public ResponseEntity<List<CompensationRoutingRecord>> listByMerchant(@PathVariable Long merchantId,
                                                                          HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        ownershipGuard.requireOwned(callerMerchantId, merchantId, "compensation-route", merchantId);
        return ResponseEntity.ok(service.listByMerchant(merchantId));
    }

    // === Request DTOs ===

    public static class RouteRequest {
        private Long merchantId;
        private String paymentId;
        private String compensationId;
        private String originalConnector;
        private BigDecimal amount;
        private String currency;

        public Long getMerchantId() { return merchantId; }
        public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
        public String getPaymentId() { return paymentId; }
        public void setPaymentId(String paymentId) { this.paymentId = paymentId; }
        public String getCompensationId() { return compensationId; }
        public void setCompensationId(String compensationId) { this.compensationId = compensationId; }
        public String getOriginalConnector() { return originalConnector; }
        public void setOriginalConnector(String originalConnector) { this.originalConnector = originalConnector; }
        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }
        public String getCurrency() { return currency; }
        public void setCurrency(String currency) { this.currency = currency; }
    }

    public static class CompleteRequest {
        private String result;
        private Long latencyMs;
        private String errorMessage;

        public String getResult() { return result; }
        public void setResult(String result) { this.result = result; }
        public Long getLatencyMs() { return latencyMs; }
        public void setLatencyMs(Long latencyMs) { this.latencyMs = latencyMs; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    }
}
