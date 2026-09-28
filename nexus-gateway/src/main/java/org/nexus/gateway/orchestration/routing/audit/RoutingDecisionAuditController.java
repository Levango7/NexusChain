package org.nexus.gateway.orchestration.routing.audit;

import jakarta.servlet.http.HttpServletRequest;
import org.nexus.gateway.security.MerchantOwnershipGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 路由决策审计查询 REST API（Wave 16 模块五）。
 *
 * <p>商户归属校验（P0 IDOR 防护沿用 Wave 15 模式）：按商户查询必须 = 认证上下文；
 * 按决策/支付查询时逐条校验记录归属，任何不属于调用方的记录返回 404（不泄露存在性）。</p>
 *
 * <p>接口列表：</p>
 * <ul>
 *   <li>{@code GET /api/v1/routing/decisions/{decisionId}} — 单条（归属校验）</li>
 *   <li>{@code GET /api/v1/routing/decisions/payment/{paymentId}} — 按支付（逐条归属校验）</li>
 *   <li>{@code GET /api/v1/routing/decisions/merchant/{merchantId}} — 按商户（归属校验）</li>
 *   <li>{@code GET /api/v1/routing/decisions/experiment/{experimentId}} — 按实验（平台级，实验分析）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/routing/decisions")
public class RoutingDecisionAuditController {

    private static final Logger log = LoggerFactory.getLogger(RoutingDecisionAuditController.class);

    private final RoutingAuditService service;
    private final MerchantOwnershipGuard ownershipGuard;

    public RoutingDecisionAuditController(RoutingAuditService service,
                                          MerchantOwnershipGuard ownershipGuard) {
        this.service = service;
        this.ownershipGuard = ownershipGuard;
    }

    @GetMapping("/{decisionId}")
    public ResponseEntity<RoutingDecisionRecord> get(@PathVariable String decisionId,
                                                     HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        RoutingDecisionRecord record = service.get(decisionId).orElse(null);
        if (record == null) {
            return ResponseEntity.notFound().build();
        }
        ownershipGuard.requireOwned(callerMerchantId, record.getMerchantId(), "routing-decision", null);
        return ResponseEntity.ok(record);
    }

    @GetMapping("/payment/{paymentId}")
    public ResponseEntity<List<RoutingDecisionRecord>> listByPayment(@PathVariable String paymentId,
                                                                     HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        List<RoutingDecisionRecord> records = service.listByPayment(paymentId);
        for (RoutingDecisionRecord record : records) {
            ownershipGuard.requireOwned(callerMerchantId, record.getMerchantId(), "routing-decision", null);
        }
        return ResponseEntity.ok(records);
    }

    @GetMapping("/merchant/{merchantId}")
    public ResponseEntity<List<RoutingDecisionRecord>> listByMerchant(@PathVariable Long merchantId,
                                                                      HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        ownershipGuard.requireOwned(callerMerchantId, merchantId, "routing-decision", merchantId);
        return ResponseEntity.ok(service.listByMerchant(merchantId));
    }

    @GetMapping("/experiment/{experimentId}")
    public List<RoutingDecisionRecord> listByExperiment(@PathVariable String experimentId) {
        return service.listByExperiment(experimentId);
    }
}
