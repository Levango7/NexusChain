package org.nexus.bridge;

import org.nexus.bridge.model.BridgeTransaction;
import org.nexus.bridge.saga.BridgeSagaCoordinator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 跨链桥 REST 入口。
 *
 * <p><b>两层入口的区别（#59）：</b>四个单步端点（{@code /lock} {@code /mint} {@code /burn}
 * {@code /unlock}）各自只跑一步、<b>不记 saga、不补偿</b>：调用方必须自己保证第二步也发出去，
 * 中途失败就靠人工去对账。两个成对端点（{@code /transfers} {@code /reversals}）走
 * {@link BridgeSagaCoordinator}，把"lock→mint / burn→unlock"作为一个事务性业务流程执行：
 * 每一步状态独立落库，第二步失败会自动补偿第一步（unlock 回释放已锁资产），
 * 未走完的 saga 由已接线的恢复/重试调度任务继续推进。</p>
 *
 * <p>单步端点保留不删：运维做单腿修复时需要它，且它们是已发布的 SDK
 * （{@code BridgeServiceFeignClient}）契约的一部分。但"跨链转账"这件事的正解是成对端点。</p>
 */
@RestController
@RequestMapping("/api/v1/bridge")
public class BridgeController {

    private final BridgeService bridgeService;
    private final BridgeSagaCoordinator sagaCoordinator;

    public BridgeController(BridgeService bridgeService, BridgeSagaCoordinator sagaCoordinator) {
        this.bridgeService = bridgeService;
        this.sagaCoordinator = sagaCoordinator;
    }

    /** 正向跨链的成对请求体：源链锁定 + 目标链铸造。 */
    public record LockMintRequest(LockRequest lock, MintRequest mint) {
    }

    /** 反向跨链的成对请求体：映射资产销毁 + 源链解锁。 */
    public record BurnUnlockRequest(BurnRequest burn, UnlockRequest unlock) {
    }

    /**
     * 正向跨链（lock → mint），带状态持久化与失败补偿。
     *
     * <p>{@code mint.lockTxId} 由协调器在 lock 完成后回填，调用方不必（也不该）自己填。
     * mint 失败时协调器会执行 unlock 补偿并抛 {@link BridgeException}，由下方
     * {@code @ExceptionHandler} 映射为 409——此时锁已释放，请求可安全重试。</p>
     */
    @PostMapping("/transfers")
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<BridgeTransaction> transfer(@RequestBody LockMintRequest request) {
        if (request == null || request.lock() == null || request.mint() == null) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(sagaCoordinator.executeLockMint(request.lock(), request.mint()));
    }

    /**
     * 反向跨链（burn → unlock），带状态持久化与失败补偿。
     */
    @PostMapping("/reversals")
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<BridgeTransaction> reversal(@RequestBody BurnUnlockRequest request) {
        if (request == null || request.burn() == null || request.unlock() == null) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(sagaCoordinator.executeBurnUnlock(request.burn(), request.unlock()));
    }

    @PostMapping("/lock")
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<BridgeTransaction> lock(@RequestBody LockRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(bridgeService.lock(request));
    }

    @PostMapping("/mint")
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<BridgeTransaction> mint(@RequestBody MintRequest request) {
        return ResponseEntity.ok(bridgeService.mint(request));
    }

    @PostMapping("/burn")
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<BridgeTransaction> burn(@RequestBody BurnRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(bridgeService.burn(request));
    }

    @PostMapping("/unlock")
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<BridgeTransaction> unlock(@RequestBody UnlockRequest request) {
        return ResponseEntity.ok(bridgeService.unlock(request));
    }

    @GetMapping("/tx/{txId}")
    public ResponseEntity<BridgeTransaction> getTx(@PathVariable String txId) {
        BridgeTransaction tx = bridgeService.getTransaction(txId);
        return tx != null ? ResponseEntity.ok(tx) : ResponseEntity.notFound().build();
    }

    @GetMapping("/tx")
    public ResponseEntity<BridgeTransaction> getBySourceHash(@RequestParam String sourceTxHash) {
        BridgeTransaction tx = bridgeService.getTransactionBySourceHash(sourceTxHash);
        return tx != null ? ResponseEntity.ok(tx) : ResponseEntity.notFound().build();
    }

    @GetMapping("/status")
    public ResponseEntity<BridgeStatus> status() {
        return ResponseEntity.ok(bridgeService.getStatus());
    }

    @PostMapping("/pause")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> pause(@RequestBody Map<String, String> body) {
        bridgeService.pause(body.get("validatorId"));
        return ResponseEntity.ok(java.util.Collections.singletonMap("message", "Bridge paused"));
    }

    @PostMapping("/resume")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> resume(@RequestBody Map<String, Object> body) {
        Object validatorIdsObj = body.get("validatorIds");
        if (validatorIdsObj == null) {
            return ResponseEntity.badRequest().body(java.util.Collections.singletonMap("error", "validatorIds is required"));
        }
        @SuppressWarnings("unchecked")
        java.util.List<String> idList = (java.util.List<String>) validatorIdsObj;
        if (idList == null || idList.isEmpty()) {
            return ResponseEntity.badRequest().body(java.util.Collections.singletonMap("error", "validatorIds must not be empty"));
        }
        Set<String> ids = idList.stream().collect(Collectors.toSet());
        bridgeService.resume(ids);
        return ResponseEntity.ok(java.util.Collections.singletonMap("message", "Bridge resumed"));
    }

    @ExceptionHandler(BridgeException.class)
    public ResponseEntity<Map<String, Object>> handleBridgeException(BridgeException e) {
        Map<String, Object> err = new java.util.HashMap<>();
        err.put("code", 40900);
        err.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(err);
    }
}