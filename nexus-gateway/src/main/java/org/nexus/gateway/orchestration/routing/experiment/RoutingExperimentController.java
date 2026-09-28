package org.nexus.gateway.orchestration.routing.experiment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

/**
 * 路由 A/B 实验 REST API（Wave 16 模块四）。
 *
 * <p>平台级实验管理（无商户归属维度；端点位于 {@code /api/v1/**}，
 * 受 ApiKeyInterceptor 认证保护）。</p>
 *
 * <p>接口列表：</p>
 * <ul>
 *   <li>{@code GET /api/v1/routing/experiments} — 全部实验</li>
 *   <li>{@code GET /api/v1/routing/experiments/running} — 运行中实验</li>
 *   <li>{@code POST /api/v1/routing/experiments} — 创建（CREATED 状态）</li>
 *   <li>{@code POST /api/v1/routing/experiments/{id}/start|pause} — 状态流转</li>
 *   <li>{@code POST /api/v1/routing/experiments/{id}/finish?completed=true|false} — 结束</li>
 *   <li>{@code POST /api/v1/routing/experiments/{id}/assign} — 支付分流（幂等确定性）</li>
 *   <li>{@code POST /api/v1/routing/experiments/{id}/outcome} — 结果回填</li>
 *   <li>{@code GET /api/v1/routing/experiments/{id}/evaluate} — 显著性评估</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/routing/experiments")
public class RoutingExperimentController {

    private static final Logger log = LoggerFactory.getLogger(RoutingExperimentController.class);

    private final RoutingExperimentService service;

    public RoutingExperimentController(RoutingExperimentService service) {
        this.service = service;
    }

    @GetMapping
    public List<RoutingExperiment> listAll() {
        return service.listAll();
    }

    @GetMapping("/running")
    public List<RoutingExperiment> listRunning() {
        return service.listRunning();
    }

    @PostMapping
    public ResponseEntity<RoutingExperiment> create(@RequestBody RoutingExperiment experiment,
                                                    @RequestHeader(value = "X-Operator", required = false) String operator) {
        try {
            return ResponseEntity.ok(service.create(experiment, operator != null ? operator : "api"));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/{id}/start")
    public ResponseEntity<RoutingExperiment> start(@PathVariable String id) {
        try {
            return ResponseEntity.ok(service.start(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<RoutingExperiment> pause(@PathVariable String id) {
        try {
            return ResponseEntity.ok(service.pause(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/{id}/finish")
    public ResponseEntity<RoutingExperiment> finish(@PathVariable String id,
                                                    @RequestParam(defaultValue = "true") boolean completed) {
        try {
            return ResponseEntity.ok(service.finish(id, completed));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/{id}/assign")
    public ResponseEntity<RoutingExperimentService.Assignment> assign(@PathVariable String id,
                                                                      @RequestBody AssignRequest request) {
        Optional<RoutingExperimentService.Assignment> assignment =
                service.assign(id, request.getPaymentId());
        return assignment.map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    @PostMapping("/{id}/outcome")
    public ResponseEntity<Void> outcome(@PathVariable String id, @RequestBody OutcomeRequest request) {
        service.recordOutcome(id, request.getGroupId(), request.isSuccess(),
                request.getLatencyMs() != null ? request.getLatencyMs() : 0L,
                request.getCostBps() != null ? request.getCostBps() : 0);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/{id}/evaluate")
    public ResponseEntity<RoutingExperimentService.Evaluation> evaluate(@PathVariable String id) {
        try {
            return ResponseEntity.ok(service.evaluate(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // === Request DTOs ===

    public static class AssignRequest {
        private String paymentId;

        public String getPaymentId() { return paymentId; }
        public void setPaymentId(String paymentId) { this.paymentId = paymentId; }
    }

    public static class OutcomeRequest {
        private String groupId;
        private boolean success;
        private Long latencyMs;
        private Integer costBps;

        public String getGroupId() { return groupId; }
        public void setGroupId(String groupId) { this.groupId = groupId; }
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public Long getLatencyMs() { return latencyMs; }
        public void setLatencyMs(Long latencyMs) { this.latencyMs = latencyMs; }
        public Integer getCostBps() { return costBps; }
        public void setCostBps(Integer costBps) { this.costBps = costBps; }
    }
}
