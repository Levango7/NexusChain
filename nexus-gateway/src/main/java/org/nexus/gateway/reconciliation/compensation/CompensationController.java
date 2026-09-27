package org.nexus.gateway.reconciliation.compensation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 补偿记录 REST API。
 *
 * <p>接口列表：</p>
 * <ul>
 *   <li>{@code GET /api/reconciliation/compensations/{id}} — 查询单条补偿记录</li>
 *   <li>{@code GET /api/reconciliation/compensations/discrepancy/{discrepancyId}} — 按差错ID查询补偿</li>
 *   <li>{@code GET /api/reconciliation/compensations/merchant/{merchantId}} — 查询商户补偿记录</li>
 *   <li>{@code POST /api/reconciliation/compensations/{id}/execute} — 执行补偿</li>
 *   <li>{@code POST /api/reconciliation/compensations/execute-all-pending} — 批量执行所有PENDING补偿</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/reconciliation/compensations")
public class CompensationController {

    private static final Logger log = LoggerFactory.getLogger(CompensationController.class);

    private final AutoCompensationService autoCompensationService;

    public CompensationController(AutoCompensationService autoCompensationService) {
        this.autoCompensationService = autoCompensationService;
    }

    /**
     * 查询单条补偿记录。
     */
    @GetMapping("/{id}")
    public ResponseEntity<CompensationRecord> getCompensation(@PathVariable Long id) {
        return autoCompensationService.getCompensation(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 按差错 ID 查询补偿记录。
     */
    @GetMapping("/discrepancy/{discrepancyId}")
    public ResponseEntity<CompensationRecord> getCompensationByDiscrepancy(
            @PathVariable Long discrepancyId) {
        return autoCompensationService.getCompensationByDiscrepancy(discrepancyId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 查询商户的所有补偿记录。
     */
    @GetMapping("/merchant/{merchantId}")
    public ResponseEntity<List<CompensationRecord>> getCompensationsByMerchant(
            @PathVariable Long merchantId) {
        List<CompensationRecord> records = autoCompensationService.getCompensationsByMerchant(merchantId);
        return ResponseEntity.ok(records);
    }

    /**
     * 执行单条补偿。
     */
    @PostMapping("/{id}/execute")
    public ResponseEntity<CompensationRecord> executeCompensation(@PathVariable Long id) {
        log.info("[CompensationController] 执行补偿: id={}", id);
        try {
            CompensationRecord result = autoCompensationService.executeCompensation(id);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 批量执行所有 PENDING 状态的补偿记录。
     */
    @PostMapping("/execute-all-pending")
    public ResponseEntity<List<CompensationRecord>> executeAllPending() {
        log.info("[CompensationController] 批量执行所有 PENDING 补偿");
        List<CompensationRecord> results = autoCompensationService.executeAllPending();
        return ResponseEntity.ok(results);
    }
}