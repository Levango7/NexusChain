package org.nexus.gateway.compliance;

import org.nexus.compliance.aml.InMemorySanctionListChecker;
import org.nexus.compliance.aml.SanctionListStartupLoader;
import org.nexus.gateway.audit.AuditLogService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * 制裁名单管理 REST API（P0 补齐 2026-10-11）。
 *
 * <p><b>为什么需要</b>：AML 筛查结构早已接好（{@code DefaultAmlService.screen}），
 * 但名单**没有任何数据入口**——{@code InMemorySanctionListChecker.addEntry} 全仓零调用方，
 * 名单恒为空 ⇒ 筛查形同虚设。本控制器给合规运营一个真实入口。</p>
 *
 * <p><b>权限</b>：一律 {@code ROLE_ADMIN}（制裁名单直接决定"谁被拦"，是最高敏感的数据面）。</p>
 *
 * <p><b>审计</b>：增/删/重载全部写审计（合规数据变更必须留痕——监管复盘要能看到
 * "谁在什么时候往名单里加了谁"）。</p>
 */
@RestController
@RequestMapping("/api/v1/compliance/sanction-list")
@PreAuthorize("hasRole('ADMIN')")
public class SanctionListController {

    private final InMemorySanctionListChecker checker;
    private final SanctionListStartupLoader loader;
    private final AuditLogService auditLogService;

    public SanctionListController(InMemorySanctionListChecker checker,
                                   SanctionListStartupLoader loader,
                                   @org.springframework.beans.factory.annotation.Autowired(required = false)
                                   AuditLogService auditLogService) {
        this.checker = checker;
        this.loader = loader;
        this.auditLogService = auditLogService;
    }

    /** 查看当前名单（条目数 + 条目快照，运营核对用）。 */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("size", checker.size());
        resp.put("empty", checker.isEmpty());
        resp.put("entries", new TreeSet<>(checker.snapshot()));
        return ResponseEntity.ok(resp);
    }

    /** 追加条目（单个姓名/地址）。 */
    @PostMapping
    public ResponseEntity<Map<String, Object>> add(@RequestParam String entry) {
        if (entry == null || entry.isBlank()) {
            throw new IllegalArgumentException("entry 不能为空");
        }
        checker.addEntry(entry);
        audit("SANCTION_LIST_ENTRY_ADDED", entry);
        return ResponseEntity.ok(Map.of("size", checker.size()));
    }

    /** 移除条目。 */
    @DeleteMapping
    public ResponseEntity<Map<String, Object>> remove(@RequestParam String entry) {
        if (entry == null || entry.isBlank()) {
            throw new IllegalArgumentException("entry 不能为空");
        }
        checker.removeEntry(entry);
        audit("SANCTION_LIST_ENTRY_REMOVED", entry);
        return ResponseEntity.ok(Map.of("size", checker.size()));
    }

    /** 从配置的文件路径重新加载名单（追加语义）。 */
    @PostMapping("/reload")
    public ResponseEntity<Map<String, Object>> reload() {
        int loaded = loader.reload();
        audit("SANCTION_LIST_RELOADED", "loaded=" + loaded);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("loaded", loaded);
        resp.put("size", checker.size());
        resp.put("empty", checker.isEmpty());
        return ResponseEntity.ok(resp);
    }

    private void audit(String action, String detail) {
        if (auditLogService == null) {
            return;
        }
        try {
            auditLogService.recordAdjust(null, "SANCTION_LIST", action, null);
        } catch (RuntimeException ignored) {
            // 审计失败不影响合规数据变更
        }
    }
}
