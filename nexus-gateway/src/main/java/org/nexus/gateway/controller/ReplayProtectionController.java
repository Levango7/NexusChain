package org.nexus.gateway.controller;

import org.nexus.gateway.security.replay.ReplayInterceptionStatsService;
import org.nexus.gateway.security.replay.ReplayProtectionConfig;
import org.nexus.gateway.security.replay.ReplayProtectionConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 防重放保护配置与统计 REST API。
 *
 * <p>管理端点，使用 {@code @PreAuthorize("hasRole('ADMIN')")} 保护。
 * 提供防重放参数配置的创建/更新/查询，以及拦截统计查询。</p>
 *
 * <p>设计依据：Wave 12 设计文档 §4.2、§6.1。</p>
 *
 * <p><b>安全审查</b>：类级 {@code @PreAuthorize("hasRole('ADMIN')")} 确保所有端点
 * 仅 ADMIN 角色可访问，遵循双层鉴权模型的方法安全层。（来源经验：
 * 2026-09-17-class-level-preauthorize-plus-require-tenant-double-defense）</p>
 */
@RestController
@RequestMapping("/api/v1/security")
@PreAuthorize("hasRole('ADMIN')")
public class ReplayProtectionController {

    private static final Logger log = LoggerFactory.getLogger(ReplayProtectionController.class);

    private final ReplayProtectionConfigService configService;
    private final ReplayInterceptionStatsService statsService;

    public ReplayProtectionController(ReplayProtectionConfigService configService,
                                       ReplayInterceptionStatsService statsService) {
        this.configService = configService;
        this.statsService = statsService;
    }

    /**
     * 创建或更新防重放配置。
     *
     * <p>若租户已有配置则更新，否则创建新配置。参数范围校验由
     * {@link ReplayProtectionConfigService#updateConfig} 执行。</p>
     *
     * @param request 配置请求体
     * @return 已保存的配置
     */
    @PostMapping("/replay-configs")
    public ResponseEntity<ReplayProtectionConfig> createOrUpdateConfig(@RequestBody ReplayConfigRequest request) {
        log.info("Creating/updating replay protection config: tenant={}, replayWindowMs={}, nonceMinLengthBytes={}, idempotencyTtlHours={}",
                request.getTenantId(), request.getReplayWindowMs(), request.getNonceMinLengthBytes(),
                request.getIdempotencyTtlHours());
        ReplayProtectionConfig config = configService.updateConfig(
                request.getTenantId(),
                request.getReplayWindowMs(),
                request.getNonceMinLengthBytes(),
                request.getIdempotencyTtlHours()
        );
        return ResponseEntity.ok(config);
    }

    /**
     * 查询指定租户的防重放配置。
     *
     * @param tenantId 租户 ID
     * @return 配置信息（无配置记录时返回默认值）
     */
    @GetMapping("/replay-configs/{tenantId}")
    public ResponseEntity<ReplayProtectionConfig> getConfig(@PathVariable String tenantId) {
        ReplayProtectionConfig config = configService.getConfig(tenantId);
        return ResponseEntity.ok(config);
    }

    /**
     * 查询防重放拦截统计。
     *
     * <p><b>读侧活着，写侧没人喂</b>（现查 {@code master}，2026-10-24）：
     * {@code ReplayInterceptionStatsService.recordInterception(...)} 在全部 main 源码里
     * <b>零调用方</b>——拦截发生在 {@code RequestSignatureInterceptor} 里，它只发 Prometheus 计数
     * （{@code nexus_replay_interceptions_total}，#127 接上的）。所以这个端点在当前实现下
     * <b>恒返回空/零</b>，不是"没有攻击"，而是"没人往这张表写"。</p>
     *
     * <p>已裁定（台账 #52）不在这里补 DB 写：拦截速率是攻击者可控的，按请求落库等于把
     * 一次重放尝试换成一次写放大；跨副本的准确计数归 Prometheus（Micrometer 计数器是每 JVM
     * 的，聚合在指标侧做）。要启用这张表必须先有"为什么要按租户留存拦截行"的裁定，
     * 见 {@code ReplayInterceptionStatsService} 的类注释。</p>
     *
     * @param tenantId  租户 ID
     * @param from      开始时间（ISO 格式，如 2026-09-26T00:00:00）
     * @param to        结束时间（ISO 格式）
     * @return 统计结果（当前实现下恒为空/零，理由见上）
     */
    @GetMapping("/replay-stats")
    public ResponseEntity<Map<String, Object>> getStats(
            @RequestParam String tenantId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        Map<String, Object> stats = statsService.getStats(tenantId, from, to);
        return ResponseEntity.ok(stats);
    }

    // --- Request DTO ---

    public static class ReplayConfigRequest {
        private String tenantId;
        private Long replayWindowMs;
        private Integer nonceMinLengthBytes;
        private Integer idempotencyTtlHours;

        public String getTenantId() { return tenantId; }
        public void setTenantId(String tenantId) { this.tenantId = tenantId; }
        public Long getReplayWindowMs() { return replayWindowMs; }
        public void setReplayWindowMs(Long replayWindowMs) { this.replayWindowMs = replayWindowMs; }
        public Integer getNonceMinLengthBytes() { return nonceMinLengthBytes; }
        public void setNonceMinLengthBytes(Integer nonceMinLengthBytes) { this.nonceMinLengthBytes = nonceMinLengthBytes; }
        public Integer getIdempotencyTtlHours() { return idempotencyTtlHours; }
        public void setIdempotencyTtlHours(Integer idempotencyTtlHours) { this.idempotencyTtlHours = idempotencyTtlHours; }
    }
}