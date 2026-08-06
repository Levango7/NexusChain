package org.nexus.gateway.execution;

import org.nexus.settlement.execution.OnChainExecutionChannel;
import org.nexus.settlement.execution.TransactionRequest;
import org.nexus.settlement.execution.TransactionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST controller exposing {@link OnChainExecutionChannel} over HTTP.
 * <p>
 * Allows out-of-process services (e.g. nexus-exchange-wallet) to invoke the
 * gateway's on-chain execution pipeline without a process-local dependency
 * on nexus-settlement. Endpoints:
 * </p>
 * <ul>
 *   <li>{@code POST /api/v1/execution/execute} — execute a transaction</li>
 *   <li>{@code GET  /api/v1/execution/status?txHash=...} — query confirmation status</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/execution")
public class OnChainExecutionController {

    private static final Logger log = LoggerFactory.getLogger(OnChainExecutionController.class);

    private final OnChainExecutionChannel executionChannel;

    public OnChainExecutionController(OnChainExecutionChannel executionChannel) {
        this.executionChannel = executionChannel;
    }

    /**
     * Execute an on-chain transaction.
     *
     * @param request transaction request body
     * @return execution result
     */
    @PostMapping("/execute")
    public ResponseEntity<TransactionResult> execute(@RequestBody TransactionRequest request) {
        log.info("execute endpoint called: type={}, requestId={}",
                request != null ? request.getType() : null,
                request != null ? request.getRequestId() : null);
        TransactionResult result = executionChannel.execute(request);
        return ResponseEntity.ok(result);
    }

    /**
     * Query on-chain transaction confirmation status.
     *
     * @param txHash transaction hash
     * @return status result
     */
    @GetMapping("/status")
    public ResponseEntity<TransactionResult> status(@RequestParam String txHash) {
        TransactionResult result = executionChannel.queryStatus(txHash);
        return ResponseEntity.ok(result);
    }

    /**
     * Handle illegal-argument errors (validation failures) as 400 Bad Request.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArg(IllegalArgumentException e) {
        log.warn("execute validation failed: {}", e.getMessage());
        return ResponseEntity.badRequest().body(Map.of(
                "status", TransactionResult.Status.FAILED.name(),
                "error", e.getMessage()));
    }
}