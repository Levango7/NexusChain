package org.nexus.gateway.orchestration.routing.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 渠道健康度 REST API（Wave 16 模块二）。
 *
 * <p>平台级运行态数据（无商户归属维度；端点位于 {@code /api/v1/**}，
 * 受 ApiKeyInterceptor 认证保护）。</p>
 *
 * <p>接口列表：</p>
 * <ul>
 *   <li>{@code GET /api/v1/routing/health} — 全部已观测 connector 的健康度</li>
 *   <li>{@code GET /api/v1/routing/health/{connectorId}} — 单个 connector 健康度</li>
 *   <li>{@code GET /api/v1/routing/health/{connectorId}/history?limit=50} — 最近历史样本</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/routing/health")
public class ChannelHealthController {

    private static final Logger log = LoggerFactory.getLogger(ChannelHealthController.class);

    private final ChannelHealthService service;

    public ChannelHealthController(ChannelHealthService service) {
        this.service = service;
    }

    @GetMapping
    public List<ChannelHealthService.ConnectorHealthScore> all() {
        return service.evaluateAll();
    }

    @GetMapping("/{connectorId}")
    public ResponseEntity<ChannelHealthService.ConnectorHealthScore> one(@PathVariable String connectorId) {
        return ResponseEntity.ok(service.evaluate(connectorId));
    }

    @GetMapping("/{connectorId}/history")
    public List<ChannelHealthHistory> history(@PathVariable String connectorId,
                                              @RequestParam(defaultValue = "50") int limit) {
        return service.history(connectorId, limit);
    }
}
