package org.nexus.gateway.sandbox.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 沙箱配置检查控制器 — 提供配置完整性检查的 REST API 端点。
 *
 * <p>调用 {@link SandboxConfigValidator} 执行全量配置检查，返回 JSON 格式的配置检查报告，
 * 包含微信/支付宝渠道的启用状态、sandbox 模式、缺失配置项列表等关键信息。</p>
 *
 * <p>仅在 sandbox profile 下激活，不暴露到生产环境。
 * 编码规范遵循：构造器注入、@Profile 限定环境（经验来源：2026-09-20-spring-boot-rest-controller-conventions）。</p>
 *
 * <p>返回结构示例：</p>
 * <pre>{@code
 * {
 *   "timestamp": "2026-09-27T10:00:00Z",
 *   "wechatConfigured": true,
 *   "alipayConfigured": false,
 *   "wechatEnabled": true,
 *   "wechatSandbox": true,
 *   "wechatMissingItems": [],
 *   "alipayEnabled": true,
 *   "alipaySandbox": false,
 *   "alipayMissingItems": ["nexus.connectors.alipay.merchant-private-key"],
 *   "missingItems": ["nexus.connectors.alipay.merchant-private-key"],
 *   "hasRealModeMissingItems": true
 * }
 * }</pre>
 */
@RestController
@RequestMapping("/api/sandbox/config-check")
@Profile("sandbox")
public class SandboxConfigCheckController {

    private static final Logger log = LoggerFactory.getLogger(SandboxConfigCheckController.class);

    private final SandboxConfigValidator validator;

    /**
     * 构造器注入 — 遵循项目统一的依赖注入规范（经验来源：2026-09-20-spring-boot-rest-controller-conventions）。
     *
     * @param validator 沙箱配置验证器，用于执行全量配置检查
     */
    public SandboxConfigCheckController(SandboxConfigValidator validator) {
        this.validator = validator;
    }

    /**
     * 执行配置完整性检查，返回 JSON 格式的配置检查报告。
     *
     * <p>报告包含以下字段：</p>
     * <ul>
     *   <li>{@code wechatConfigured} — 微信渠道是否已启用且配置完整</li>
     *   <li>{@code alipayConfigured} — 支付宝渠道是否已启用且配置完整</li>
     *   <li>{@code wechatEnabled} / {@code alipayEnabled} — 各渠道是否启用</li>
     *   <li>{@code wechatSandbox} / {@code alipaySandbox} — 各渠道是否为 sandbox 模式</li>
     *   <li>{@code wechatMissingItems} / {@code alipayMissingItems} — 各渠道缺失配置项列表</li>
     *   <li>{@code missingItems} — 全量缺失配置项列表（微信 + 支付宝合并）</li>
     *   <li>{@code hasRealModeMissingItems} — 是否存在真实 API 模式下的缺失项（需 WARN 级别关注）</li>
     * </ul>
     *
     * @return 配置检查报告，HTTP 200
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> checkConfig() {
        log.info("[ConfigCheck] 开始配置完整性检查");

        SandboxConfigValidator.ConfigCheckResult result = validator.validate();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("timestamp", Instant.now().toString());

        // 微信渠道配置状态
        boolean wechatConfigured = result.isWechatEnabled() && result.getWechatMissingItems().isEmpty();
        report.put("wechatConfigured", wechatConfigured);
        report.put("wechatEnabled", result.isWechatEnabled());
        report.put("wechatSandbox", result.isWechatSandbox());
        report.put("wechatMissingItems", result.getWechatMissingItems());

        // 支付宝渠道配置状态
        boolean alipayConfigured = result.isAlipayEnabled() && result.getAlipayMissingItems().isEmpty();
        report.put("alipayConfigured", alipayConfigured);
        report.put("alipayEnabled", result.isAlipayEnabled());
        report.put("alipaySandbox", result.isAlipaySandbox());
        report.put("alipayMissingItems", result.getAlipayMissingItems());

        // 全量缺失项与真实模式缺失项标记
        List<String> missingItems = result.getMissingItems();
        report.put("missingItems", missingItems);
        report.put("hasRealModeMissingItems", result.hasRealModeMissingItems());

        log.info("[ConfigCheck] 配置完整性检查完成: wechatConfigured={}, alipayConfigured={}, missingItems={}, hasRealModeMissingItems={}",
                wechatConfigured, alipayConfigured, missingItems.size(), result.hasRealModeMissingItems());

        return ResponseEntity.ok(report);
    }
}