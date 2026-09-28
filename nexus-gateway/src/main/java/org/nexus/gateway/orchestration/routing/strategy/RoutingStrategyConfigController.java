package org.nexus.gateway.orchestration.routing.strategy;

import org.nexus.gateway.orchestration.routing.strategy.RoutingStrategyConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 多目标路由策略配置 REST API（Wave 16 模块一）。
 *
 * <p>平台级路由策略配置（无商户归属维度，不涉及 IDOR；端点位于
 * {@code /api/v1/**}，受 ApiKeyInterceptor 认证保护）。</p>
 *
 * <p>接口列表：</p>
 * <ul>
 *   <li>{@code GET /api/v1/routing/strategy-configs} — 全部配置</li>
 *   <li>{@code GET /api/v1/routing/strategy-configs/enabled} — 生效配置</li>
 *   <li>{@code POST /api/v1/routing/strategy-configs} — 创建</li>
 *   <li>{@code PUT /api/v1/routing/strategy-configs/{id}} — 更新</li>
 *   <li>{@code DELETE /api/v1/routing/strategy-configs/{id}} — 删除</li>
 *   <li>{@code POST /api/v1/routing/strategy-configs/evict-cache} — 强制热加载</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/routing/strategy-configs")
public class RoutingStrategyConfigController {

    private static final Logger log = LoggerFactory.getLogger(RoutingStrategyConfigController.class);

    private final MultiObjectiveRoutingService service;

    public RoutingStrategyConfigController(MultiObjectiveRoutingService service) {
        this.service = service;
    }

    @GetMapping
    public List<RoutingStrategyConfig> listAll() {
        return service.listAll();
    }

    @GetMapping("/enabled")
    public List<RoutingStrategyConfig> listEnabled() {
        return service.listEnabled();
    }

    @PostMapping
    public ResponseEntity<RoutingStrategyConfig> create(@RequestBody RoutingStrategyConfig config,
                                                        @RequestHeader(value = "X-Operator", required = false) String operator) {
        try {
            return ResponseEntity.ok(service.create(config, operator != null ? operator : "api"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<RoutingStrategyConfig> update(@PathVariable Long id,
                                                        @RequestBody RoutingStrategyConfig update,
                                                        @RequestHeader(value = "X-Operator", required = false) String operator) {
        try {
            return ResponseEntity.ok(service.update(id, update, operator != null ? operator : "api"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        try {
            service.delete(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/evict-cache")
    public ResponseEntity<Void> evictCache() {
        service.evictCache();
        return ResponseEntity.noContent().build();
    }
}
