package org.nexus.gateway.orchestration.routing.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 路由监控 REST API（Wave 16 模块七）。
 *
 * <p>平台级运行态数据（无商户归属维度；端点位于 {@code /api/v1/**}，
 * 受 ApiKeyInterceptor 认证保护）。</p>
 *
 * <p>接口列表：</p>
 * <ul>
 *   <li>{@code GET /api/v1/routing/monitor/anomalies} — 最近异常（内存最近 100 条）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/routing/monitor")
public class RoutingMonitorController {

    private static final Logger log = LoggerFactory.getLogger(RoutingMonitorController.class);

    private final RoutingMonitorService service;

    public RoutingMonitorController(RoutingMonitorService service) {
        this.service = service;
    }

    @GetMapping("/anomalies")
    public List<RoutingMonitorService.Anomaly> anomalies() {
        return service.recentAnomalies();
    }
}
