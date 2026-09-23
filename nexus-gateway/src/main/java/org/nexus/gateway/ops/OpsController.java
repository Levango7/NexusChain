package org.nexus.gateway.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 运维管理 REST API 控制器。
 *
 * <p>路径前缀 {@code /api/v1/ops}，所有端点均要求 {@code ADMIN} 角色。
 * 提供配置管理、缓存管理、线程池/连接池监控、系统信息、详细健康检查等运维端点。</p>
 *
 * <h3>端点概览</h3>
 * <ul>
 *   <li><b>配置管理</b>：GET /config, POST /config/refresh, GET /config/diff</li>
 *   <li><b>缓存管理</b>：POST /cache/clear, GET /cache/stats, POST /cache/clear-all</li>
 *   <li><b>线程池监控</b>：GET /threadpools</li>
 *   <li><b>连接池监控</b>：GET /connection-pools</li>
 *   <li><b>系统信息</b>：GET /system, GET /health/detailed</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/ops")
@PreAuthorize("hasRole('ADMIN')")
@ConditionalOnProperty(name = "nexus.ops.enabled", havingValue = "true", matchIfMissing = true)
public class OpsController {

    private static final Logger log = LoggerFactory.getLogger(OpsController.class);

    private final OpsService opsService;

    @Value("${nexus.ops.enabled:true}")
    private boolean opsEnabled;

    public OpsController(OpsService opsService) {
        this.opsService = opsService;
    }

    // ==================== 配置管理 ====================

    /**
     * 查看当前关键配置（脱敏后）。
     *
     * @return 脱敏后的配置键值映射
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        log.info("查看当前配置（脱敏）");
        return ResponseEntity.ok(opsService.getConfig());
    }

    /**
     * 触发配置刷新。
     *
     * <p>通过 Spring Cloud Context 的 RefreshScope 触发配置重新加载。
     * Nacos 配置中心变更后，调用此端点可使本地 @RefreshScope Bean 重新初始化。</p>
     *
     * @return 刷新结果
     */
    @PostMapping("/config/refresh")
    public ResponseEntity<Map<String, Object>> refreshConfig() {
        log.info("触发配置刷新");
        return ResponseEntity.ok(opsService.refreshConfig());
    }

    /**
     * 查看配置差异（本地 vs Nacos 远程）。
     *
     * @return 配置差异映射
     */
    @GetMapping("/config/diff")
    public ResponseEntity<Map<String, Object>> getConfigDiff() {
        log.info("查看配置差异");
        return ResponseEntity.ok(opsService.getConfigDiff());
    }

    // ==================== 缓存管理 ====================

    /**
     * 清除指定缓存。
     *
     * @param body 请求体，需包含 cacheName 字段
     * @return 清除结果
     */
    @PostMapping("/cache/clear")
    public ResponseEntity<Map<String, Object>> clearCache(@RequestBody Map<String, String> body) {
        String cacheName = body.get("cacheName");
        if (cacheName == null || cacheName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", "cacheName 参数不能为空"
            ));
        }
        log.info("清除缓存: {}", cacheName);
        return ResponseEntity.ok(opsService.clearCache(cacheName));
    }

    /**
     * 查看缓存统计信息。
     *
     * @return 缓存统计列表
     */
    @GetMapping("/cache/stats")
    public ResponseEntity<List<CacheStatsDTO>> getCacheStats() {
        log.info("查看缓存统计");
        return ResponseEntity.ok(opsService.getCacheStats());
    }

    /**
     * 清除所有缓存。
     *
     * @return 清除结果
     */
    @PostMapping("/cache/clear-all")
    public ResponseEntity<Map<String, Object>> clearAllCaches() {
        log.info("清除所有缓存");
        return ResponseEntity.ok(opsService.clearAllCaches());
    }

    // ==================== 线程池/连接池监控 ====================

    /**
     * 查看线程池状态。
     *
     * @return 线程池统计列表
     */
    @GetMapping("/threadpools")
    public ResponseEntity<List<ThreadPoolStatsDTO>> getThreadPoolStats() {
        log.info("查看线程池状态");
        return ResponseEntity.ok(opsService.getThreadPoolStats());
    }

    /**
     * 查看 HikariCP 连接池状态。
     *
     * @return 连接池统计
     */
    @GetMapping("/connection-pools")
    public ResponseEntity<ConnectionPoolStatsDTO> getConnectionPoolStats() {
        log.info("查看连接池状态");
        return ResponseEntity.ok(opsService.getConnectionPoolStats());
    }

    // ==================== 系统信息 ====================

    /**
     * 查看 JVM 系统信息。
     *
     * @return 系统信息 DTO
     */
    @GetMapping("/system")
    public ResponseEntity<SystemInfoDTO> getSystemInfo() {
        log.info("查看系统信息");
        return ResponseEntity.ok(opsService.getSystemInfo());
    }

    /**
     * 查看详细健康检查结果。
     *
     * <p>包含所有 HealthIndicator 的详细结果，比 Actuator /health 端点更全面。</p>
     *
     * @return 健康检查结果映射
     */
    @GetMapping("/health/detailed")
    public ResponseEntity<Map<String, Object>> getDetailedHealth() {
        log.info("查看详细健康检查");
        return ResponseEntity.ok(opsService.getDetailedHealth());
    }
}