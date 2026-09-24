package org.nexus.gateway.orchestration.settlement;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

/**
 * 链上结算确认 REST API（Wave 9-C1-1）。
 *
 * <p>提供链上结算确认状态的查询和手动重试功能：</p>
 * <ul>
 *   <li>GET  /api/v1/settlement-confirmations/{paymentId} — 查询指定支付的确认状态</li>
 *   <li>GET  /api/v1/settlement-confirmations — 查询确认列表（支持按状态过滤、分页）</li>
 *   <li>POST /api/v1/settlement-confirmations/{paymentId}/retry — 手动触发重试</li>
 * </ul>
 *
 * @since Wave 9-C1-1 链上结算确认
 */
@RestController
@RequestMapping("/api/v1/settlement-confirmations")
@Tag(name = "Settlement Confirmations", description = "Chain settlement confirmation status and retry management")
public class ChainSettlementConfirmationController {

    private final ChainSettlementConfirmationService confirmationService;

    public ChainSettlementConfirmationController(
            ChainSettlementConfirmationService confirmationService) {
        this.confirmationService = confirmationService;
    }

    /**
     * 查询指定支付的确认状态。
     *
     * @param paymentId 支付 ID
     * @return 确认记录详情，或 404
     */
    @Operation(summary = "Get settlement confirmation status by payment ID")
    @GetMapping("/{paymentId}")
    public ResponseEntity<SettlementConfirmationRecord> getConfirmation(@PathVariable String paymentId) {
        Optional<SettlementConfirmationRecord> record = confirmationService.getConfirmation(paymentId);
        return record
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 查询确认列表（支持按状态过滤、分页）。
     *
     * @param status 可选状态过滤（PENDING/CONFIRMED/TIMED_OUT/FAILED）
     * @param page   页码（默认 0）
     * @param size   每页条数（默认 20）
     * @return 确认记录分页列表
     */
    @Operation(summary = "List settlement confirmations with optional status filter")
    @GetMapping
    public ResponseEntity<Page<SettlementConfirmationRecord>> listConfirmations(
            @RequestParam(required = false) SettlementConfirmationStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        // P1-2：数据访问下沉到 Service，Controller 不再直接依赖 Repository
        Page<SettlementConfirmationRecord> records =
                confirmationService.listConfirmations(status, pageRequest);

        return ResponseEntity.ok(records);
    }

    /**
     * 手动触发重试。
     *
     * @param paymentId 支付 ID
     * @return 更新后的确认记录，或 404
     */
    @Operation(summary = "Manually trigger a retry for settlement confirmation")
    @PostMapping("/{paymentId}/retry")
    public ResponseEntity<SettlementConfirmationRecord> retryConfirmation(@PathVariable String paymentId) {
        if (!confirmationService.existsByPaymentId(paymentId)) {
            return ResponseEntity.notFound().build();
        }

        try {
            SettlementConfirmationRecord record = confirmationService.manualRetry(paymentId);
            return ResponseEntity.ok(record);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}