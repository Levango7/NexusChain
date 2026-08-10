package org.nexus.bridge;

import org.nexus.bridge.model.BridgeTransaction;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/bridge")
public class BridgeController {

    private final BridgeService bridgeService;

    public BridgeController(BridgeService bridgeService) {
        this.bridgeService = bridgeService;
    }

    @PostMapping("/lock")
    public ResponseEntity<BridgeTransaction> lock(@RequestBody LockRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(bridgeService.lock(request));
    }

    @PostMapping("/mint")
    public ResponseEntity<BridgeTransaction> mint(@RequestBody MintRequest request) {
        return ResponseEntity.ok(bridgeService.mint(request));
    }

    @PostMapping("/burn")
    public ResponseEntity<BridgeTransaction> burn(@RequestBody BurnRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(bridgeService.burn(request));
    }

    @PostMapping("/unlock")
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
    public ResponseEntity<Map<String, String>> pause(@RequestBody Map<String, String> body) {
        bridgeService.pause(body.get("validatorId"));
        return ResponseEntity.ok(java.util.Collections.singletonMap("message", "Bridge paused"));
    }

    @PostMapping("/resume")
    public ResponseEntity<Map<String, String>> resume(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Set<String> ids = ((java.util.List<String>) body.get("validatorIds")).stream().collect(Collectors.toSet());
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