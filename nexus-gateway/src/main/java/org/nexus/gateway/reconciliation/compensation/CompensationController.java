package org.nexus.gateway.reconciliation.compensation;

import jakarta.servlet.http.HttpServletRequest;
import org.nexus.gateway.security.MerchantOwnershipGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 补偿记录 REST API。
 *
 * <p>P0-3 修复：所有端点添加商户归属校验，防止 IDOR 攻击。</p>
 *
 * <p>接口列表：</p>
 * <ul>
 *   <li>{@code GET /api/v1/reconciliation/compensations/{id}} — 查询单条补偿记录</li>
 *   <li>{@code GET /api/v1/reconciliation/compensations/discrepancy/{discrepancyId}} — 按差错ID查询补偿</li>
 *   <li>{@code GET /api/v1/reconciliation/compensations/merchant/{merchantId}} — 查询商户补偿记录</li>
 *   <li>{@code POST /api/v1/reconciliation/compensations/{id}/execute} — 执行补偿</li>
 *   <li>{@code POST /api/v1/reconciliation/compensations/execute-all-pending} — 批量执行当前商户的PENDING补偿</li>
 * </ul>
 *
 * <p>2026-09-28 路径迁移：/api/reconciliation/compensations → /api/v1/reconciliation/compensations
 * （原路径不在 ApiKeyInterceptor 拦截范围，MerchantOwnershipGuard fail-closed 不可达）。</p>
 */
@RestController
@RequestMapping("/api/v1/reconciliation/compensations")
public class CompensationController {

    private static final Logger log = LoggerFactory.getLogger(CompensationController.class);

    private final AutoCompensationService autoCompensationService;
    private final MerchantOwnershipGuard ownershipGuard;

    public CompensationController(AutoCompensationService autoCompensationService,
                                  MerchantOwnershipGuard ownershipGuard) {
        this.autoCompensationService = autoCompensationService;
        this.ownershipGuard = ownershipGuard;
    }

    /**
     * 查询单条补偿记录。
     *
     * <p>P0-3：补偿记录必须属于当前认证商户。</p>
     */
    @GetMapping("/{id}")
    public ResponseEntity<CompensationRecord> getCompensation(@PathVariable Long id,
                                                               HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        return autoCompensationService.getCompensation(id)
                .map(record -> {
                    ownershipGuard.requireOwned(callerMerchantId, record.getMerchantId(), "compensation", id);
                    return ResponseEntity.ok(record);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 按差错 ID 查询补偿记录。
     *
     * <p>P0-3：补偿记录必须属于当前认证商户。</p>
     */
    @GetMapping("/discrepancy/{discrepancyId}")
    public ResponseEntity<CompensationRecord> getCompensationByDiscrepancy(
            @PathVariable Long discrepancyId,
            HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        return autoCompensationService.getCompensationByDiscrepancy(discrepancyId)
                .map(record -> {
                    ownershipGuard.requireOwned(callerMerchantId, record.getMerchantId(), "compensation", discrepancyId);
                    return ResponseEntity.ok(record);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 查询商户的所有补偿记录。
     *
     * <p>P0-3：请求的 merchantId 必须与认证上下文一致。</p>
     */
    @GetMapping("/merchant/{merchantId}")
    public ResponseEntity<List<CompensationRecord>> getCompensationsByMerchant(
            @PathVariable Long merchantId,
            HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        ownershipGuard.requireOwned(callerMerchantId, merchantId, "merchant", merchantId);
        List<CompensationRecord> records = autoCompensationService.getCompensationsByMerchant(merchantId);
        return ResponseEntity.ok(records);
    }

    /**
     * 执行单条补偿。
     *
     * <p>P0-3：补偿记录必须属于当前认证商户。</p>
     */
    @PostMapping("/{id}/execute")
    public ResponseEntity<CompensationRecord> executeCompensation(@PathVariable Long id,
                                                                  HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);

        // 先加载 record 获取其 merchantId 进行归属校验
        CompensationRecord existing = autoCompensationService.getCompensation(id)
                .orElse(null);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        ownershipGuard.requireOwned(callerMerchantId, existing.getMerchantId(), "compensation", id);

        log.info("[CompensationController] 执行补偿: id={}", id);
        try {
            CompensationRecord result = autoCompensationService.executeCompensation(id);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 批量执行当前商户的 PENDING 状态补偿记录。
     *
     * <p>P0-3：只执行当前认证商户的 PENDING 补偿，不再执行所有商户的。</p>
     */
    @PostMapping("/execute-all-pending")
    public ResponseEntity<List<CompensationRecord>> executeAllPending(HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        log.info("[CompensationController] 批量执行商户 {} 的 PENDING 补偿", callerMerchantId);
        List<CompensationRecord> results = autoCompensationService.executeAllPendingByMerchant(callerMerchantId);
        return ResponseEntity.ok(results);
    }
}