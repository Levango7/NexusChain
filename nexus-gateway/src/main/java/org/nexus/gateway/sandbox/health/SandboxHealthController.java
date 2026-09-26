package org.nexus.gateway.sandbox.health;

import org.nexus.gateway.orchestration.connector.ConnectorHealth;
import org.nexus.gateway.orchestration.connector.ConnectorRegistry;
import org.nexus.gateway.orchestration.connector.PaymentConnector;
import org.nexus.gateway.orchestration.connectors.WeChatPlatformCertificateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 沙箱环境健康检查控制器 — 提供渠道连接器的真实 API 可达性检查端点。
 *
 * <p>核心职责：</p>
 * <ol>
 *   <li>综合健康检查：遍历所有已注册连接器，调用各自的 {@link PaymentConnector#healthCheck()}
 *       方法，汇总返回每个连接器的健康状态</li>
 *   <li>微信连接器专项检查：检查微信连接器是否启用、是否 dry-run 模式、平台证书状态</li>
 *   <li>支付宝连接器专项检查：检查支付宝连接器的健康状态</li>
 * </ol>
 *
 * <p>仅在 sandbox profile 下激活，不暴露到生产环境。</p>
 *
 * <p>编码规范遵循：构造器注入（经验来源：2026-09-20-spring-boot-rest-controller-conventions），
 * @Profile 限定环境（与 SandboxController 保持一致）。</p>
 */
@RestController
@RequestMapping("/api/sandbox/health")
@Profile("sandbox")
public class SandboxHealthController {

    private static final Logger log = LoggerFactory.getLogger(SandboxHealthController.class);

    private final ConnectorRegistry connectorRegistry;
    private final WeChatPlatformCertificateManager certificateManager;

    /**
     * 构造器注入 — 遵循项目统一的依赖注入规范。
     *
     * @param connectorRegistry   连接器注册表，用于获取所有已注册的支付连接器
     * @param certificateManager  微信平台证书管理器，用于检查证书状态
     */
    public SandboxHealthController(
            ConnectorRegistry connectorRegistry,
            WeChatPlatformCertificateManager certificateManager) {
        this.connectorRegistry = connectorRegistry;
        this.certificateManager = certificateManager;
    }

    /**
     * 综合健康检查 — 检查所有已注册连接器的健康状态及微信平台证书刷新需求。
     *
     * <p>返回结构：</p>
     * <pre>{@code
     * {
     *   "timestamp": "2026-09-27T10:00:00Z",
     *   "overall": "UP",
     *   "connectors": [
     *     {
     *       "connectorId": "wechat",
     *       "healthy": true,
     *       "message": "OK",
     *       "latencyMs": 120,
     *       "checkedAt": "2026-09-27T10:00:00Z"
     *     },
     *     ...
     *   ],
     *   "wechatCertificateNeedsRefresh": false,
     *   "activeCertificateCount": 1
     * }
     * }</pre>
     *
     * @return 综合健康报告
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> healthCheck() {
        log.info("[SandboxHealth] 开始综合健康检查");

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("timestamp", Instant.now().toString());

        // 检查每个连接器的健康状态
        List<Map<String, Object>> connectorReports = new ArrayList<>();
        boolean allHealthy = true;

        for (PaymentConnector connector : connectorRegistry.getAll()) {
            Map<String, Object> connectorReport = checkConnectorHealth(connector);
            connectorReports.add(connectorReport);

            boolean healthy = (boolean) connectorReport.get("healthy");
            if (!healthy) {
                allHealthy = false;
            }
        }

        report.put("overall", allHealthy ? "UP" : "DOWN");
        report.put("connectors", connectorReports);

        // 检查微信平台证书是否需要刷新
        boolean needsRefresh = certificateManager.needsRefresh();
        report.put("wechatCertificateNeedsRefresh", needsRefresh);

        // 获取活跃证书数量
        List<Map<String, Object>> activeCerts = certificateManager.getActiveCertificates();
        report.put("activeCertificateCount", activeCerts.size());

        log.info("[SandboxHealth] 综合健康检查完成: overall={}, connectors={}, certRefresh={}",
                allHealthy ? "UP" : "DOWN", connectorReports.size(), needsRefresh);

        return ResponseEntity.ok(report);
    }

    /**
     * 微信连接器专项健康检查 — 检查微信连接器的连接状态和平台证书详情。
     *
     * <p>返回结构：</p>
     * <pre>{@code
     * {
     *   "timestamp": "2026-09-27T10:00:00Z",
     *   "connector": {
     *     "connectorId": "wechat",
     *     "healthy": true,
     *     "message": "OK",
     *     "latencyMs": 120,
     *     "checkedAt": "2026-09-27T10:00:00Z"
     *   },
     *   "enabled": true,
     *   "dryRun": true,
     *   "certificateNeedsRefresh": false,
     *   "activeCertificates": [
     *     {
     *       "serialNo": "1234567890",
     *       "effectiveTime": "2026-01-01T00:00:00Z",
     *       "expireTime": "2027-01-01T00:00:00Z",
     *       "status": "ACTIVE"
     *     }
     *   ]
     * }
     * }</pre>
     *
     * @return 微信连接器健康报告
     */
    @GetMapping("/wechat")
    public ResponseEntity<Map<String, Object>> wechatHealth() {
        log.info("[SandboxHealth] 微信连接器专项健康检查");

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("timestamp", Instant.now().toString());

        // 查找微信连接器
        Optional<PaymentConnector> wechatConnector = connectorRegistry.get("wechat");

        if (wechatConnector.isPresent()) {
            PaymentConnector connector = wechatConnector.get();
            Map<String, Object> connectorReport = checkConnectorHealth(connector);
            report.put("connector", connectorReport);
            report.put("enabled", connector.isActive());
            report.put("dryRun", isDryRun(connector));
        } else {
            report.put("connector", null);
            report.put("enabled", false);
            report.put("dryRun", true);
            report.put("message", "WeChat connector not registered");
        }

        // 平台证书状态
        report.put("certificateNeedsRefresh", certificateManager.needsRefresh());
        report.put("activeCertificates", certificateManager.getActiveCertificates());

        log.info("[SandboxHealth] 微信连接器检查完成");
        return ResponseEntity.ok(report);
    }

    /**
     * 支付宝连接器专项健康检查 — 检查支付宝连接器的连接状态。
     *
     * <p>返回结构：</p>
     * <pre>{@code
     * {
     *   "timestamp": "2026-09-27T10:00:00Z",
     *   "connector": {
     *     "connectorId": "alipay",
     *     "healthy": true,
     *     "message": "OK",
     *     "latencyMs": 80,
     *     "checkedAt": "2026-09-27T10:00:00Z"
     *   },
     *   "enabled": true,
     *   "dryRun": true
     * }
     * }</pre>
     *
     * @return 支付宝连接器健康报告
     */
    @GetMapping("/alipay")
    public ResponseEntity<Map<String, Object>> alipayHealth() {
        log.info("[SandboxHealth] 支付宝连接器专项健康检查");

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("timestamp", Instant.now().toString());

        // 查找支付宝连接器
        Optional<PaymentConnector> alipayConnector = connectorRegistry.get("alipay");

        if (alipayConnector.isPresent()) {
            PaymentConnector connector = alipayConnector.get();
            Map<String, Object> connectorReport = checkConnectorHealth(connector);
            report.put("connector", connectorReport);
            report.put("enabled", connector.isActive());
            report.put("dryRun", isDryRun(connector));
        } else {
            report.put("connector", null);
            report.put("enabled", false);
            report.put("dryRun", true);
            report.put("message", "Alipay connector not registered");
        }

        log.info("[SandboxHealth] 支付宝连接器检查完成");
        return ResponseEntity.ok(report);
    }

    // --- 内部辅助方法 ---

    /**
     * 调用连接器的 healthCheck() 并将结果转换为 Map。
     *
     * @param connector 支付连接器
     * @return 健康状态 Map，包含 connectorId/healthy/message/latencyMs/checkedAt
     */
    private Map<String, Object> checkConnectorHealth(PaymentConnector connector) {
        ConnectorHealth health;
        try {
            health = connector.healthCheck();
        } catch (Exception e) {
            log.warn("[SandboxHealth] 连接器 {} 健康检查异常: {}", connector.getId(), e.getMessage());
            health = ConnectorHealth.down(connector.getId(), "Health check exception: " + e.getMessage());
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("connectorId", health.getConnectorId());
        report.put("healthy", health.isHealthy());
        report.put("message", health.getMessage());
        report.put("latencyMs", health.getLatencyMs());
        report.put("checkedAt", health.getCheckedAt() != null ? health.getCheckedAt().toString() : null);

        return report;
    }

    /**
     * 判断连接器是否处于 dry-run 模式。
     *
     * <p>dry-run 判定逻辑：连接器未启用、或连接器类型为 http_psp 但在沙箱环境下
     * 无法发起真实 API 调用时，视为 dry-run。具体判断依据连接器的 isActive() 状态
     * 和 healthCheck() 返回的延迟值（dry-run 模式下延迟通常为 0）。</p>
     *
     * @param connector 支付连接器
     * @return true 表示处于 dry-run 模式
     */
    private boolean isDryRun(PaymentConnector connector) {
        // 未启用的连接器视为 dry-run
        if (!connector.isActive()) {
            return true;
        }
        // 通过 healthCheck 的 latencyMs 判断：dry-run 模式下 latencyMs 通常为 0
        try {
            ConnectorHealth health = connector.healthCheck();
            return health.getLatencyMs() == 0 && health.isHealthy();
        } catch (Exception e) {
            return true;
        }
    }
}