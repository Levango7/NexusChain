package org.nexus.gateway.apiversion;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * API 版本治理 REST API。
 *
 * <p>提供版本策略的管理端点，供运维与管理员查询和配置 API 版本生命周期：</p>
 * <ul>
 *   <li>{@code GET /api/v1/api-version/policies} — 列出所有版本策略</li>
 *   <li>{@code GET /api/v1/api-version/policies/{version}} — 查看版本策略详情</li>
 *   <li>{@code PUT /api/v1/api-version/policies/{version}} — 更新版本策略（管理员）</li>
 *   <li>{@code GET /api/v1/api-version/migration-guide/{from}/{to}} — 获取迁移指南</li>
 * </ul>
 *
 * <p>注意：版本治理 API 本身使用 v1 路径，因为它属于管理端点而非业务 API。</p>
 */
@RestController
@RequestMapping("/api/v1/api-version")
@Tag(name = "API Version Governance", description = "API 版本治理：策略查询、更新与迁移指南")
public class ApiVersionGovernanceController {

    private static final Logger log = LoggerFactory.getLogger(ApiVersionGovernanceController.class);

    private final ApiVersionDeprecationService deprecationService;

    public ApiVersionGovernanceController(ApiVersionDeprecationService deprecationService) {
        this.deprecationService = deprecationService;
    }

    /**
     * 列出所有版本策略。
     */
    @Operation(summary = "List all API version policies")
    @GetMapping("/policies")
    public ResponseEntity<List<ApiVersionPolicy>> listPolicies() {
        List<ApiVersionPolicy> policies = deprecationService.listAllPolicies();
        return ResponseEntity.ok(policies);
    }

    /**
     * 查看指定版本的策略详情。
     *
     * @param version 版本标签，如 "v1"、"v2"
     */
    @Operation(summary = "Get API version policy by version")
    @GetMapping("/policies/{version}")
    public ResponseEntity<ApiVersionPolicy> getPolicy(@PathVariable String version) {
        Optional<ApiVersionPolicy> opt = deprecationService.getPolicy(version);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(opt.get());
    }

    /**
     * 更新版本策略（管理员操作）。
     *
     * <p>请求体字段均可选，仅更新提供的字段：</p>
     * <ul>
     *   <li>{@code status} — 新状态（ACTIVE/DEPRECATED/SUNSET/RETIRED）</li>
     *   <li>{@code sunsetAt} — 日落日期（ISO-8601）</li>
     *   <li>{@code successorVersion} — 后继版本</li>
     *   <li>{@code migrationGuide} — 迁移指南文本</li>
     * </ul>
     *
     * @param version 版本标签
     * @param body    更新请求体
     */
    @Operation(summary = "Update API version policy (admin)")
    @PutMapping("/policies/{version}")
    public ResponseEntity<ApiVersionPolicy> updatePolicy(@PathVariable String version,
                                                          @RequestBody Map<String, Object> body) {
        ApiVersionPolicy.VersionStatus status = null;
        if (body.containsKey("status")) {
            try {
                status = ApiVersionPolicy.VersionStatus.valueOf((String) body.get("status"));
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().build();
            }
        }

        LocalDate sunsetAt = null;
        if (body.containsKey("sunsetAt") && body.get("sunsetAt") != null) {
            try {
                sunsetAt = LocalDate.parse((String) body.get("sunsetAt"));
            } catch (Exception e) {
                return ResponseEntity.badRequest().build();
            }
        }

        String successorVersion = body.containsKey("successorVersion")
                ? (String) body.get("successorVersion") : null;
        String migrationGuide = body.containsKey("migrationGuide")
                ? (String) body.get("migrationGuide") : null;

        Optional<ApiVersionPolicy> updated = deprecationService.updatePolicy(
                version, status, sunsetAt, successorVersion, migrationGuide);

        if (updated.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        // 状态变更为 DEPRECATED/SUNSET/RETIRED 时发布事件
        if (status != null && status != ApiVersionPolicy.VersionStatus.ACTIVE) {
            deprecationService.notifyDeprecation(version);
        }

        return ResponseEntity.ok(updated.get());
    }

    /**
     * 获取迁移指南。
     *
     * @param from 源版本
     * @param to   目标版本
     */
    @Operation(summary = "Get migration guide from one version to another")
    @GetMapping("/migration-guide/{from}/{to}")
    public ResponseEntity<Map<String, String>> getMigrationGuide(@PathVariable String from,
                                                                  @PathVariable String to) {
        Optional<String> guide = deprecationService.getMigrationGuide(from, to);
        if (guide.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of(
                "from", from,
                "to", to,
                "migrationGuide", guide.get()
        ));
    }
}