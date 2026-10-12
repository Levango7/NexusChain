package org.nexus.gateway.compliance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.nexus.compliance.aml.InMemorySanctionListChecker;
import org.nexus.compliance.aml.SanctionListPackParser;
import org.nexus.compliance.aml.SanctionListStartupLoader;
import org.nexus.gateway.audit.AuditEvent;
import org.nexus.gateway.audit.AuditLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 制裁名单管理 REST API（P0 补齐 2026-10-11；2026-10-12 补「离线名单包导入」与审计修正）。
 *
 * <p><b>为什么需要</b>：AML 筛查结构早已接好（{@code DefaultAmlService.screen}），
 * 但名单**没有任何数据入口**——{@code InMemorySanctionListChecker.addEntry} 全仓零调用方，
 * 名单恒为空 ⇒ 筛查形同虚设。本控制器给合规运营一个真实入口。</p>
 *
 * <p><b>权限</b>：一律 {@code ROLE_ADMIN}（制裁名单直接决定"谁被拦"，是最高敏感的数据面）。</p>
 *
 * <p><b>审计</b>：增/删/重载/导入全部写审计，且**带操作人（JWT 主体）与具体条目/版本**。
 * 2026-10-12 修正：此前审计走 {@code recordAdjust(null, "SANCTION_LIST", action, null)}，
 * action 记对了、但**"往名单里加了哪个条目"被丢弃**、actor 恒为 {@code system}
 * —— 监管要的恰是"谁在什么时候往名单里加了谁"，缺了"谁"和"哪个"等于没留痕。</p>
 *
 * <p><b>离线名单包导入</b>（2026-10-12）：支持 OFAC SDN CSV / UN 综合名单 XML / 纯文本，
 * 见 {@link SanctionListPackParser}；每次导入落一条**版本台账**（{@code sanction_list_imports}），
 * 回答"当前生效的名单是哪个版本、从哪来、谁导的"。</p>
 */
@RestController
@RequestMapping("/api/v1/compliance/sanction-list")
@PreAuthorize("hasRole('ADMIN')")
public class SanctionListController {

    /** 导入内容上限（20MB）：制裁名单最大的 UN XML 约 10MB 级，留一倍余量防内存打爆。 */
    private static final int MAX_IMPORT_BYTES = 20 * 1024 * 1024;

    private final InMemorySanctionListChecker checker;
    private final SanctionListStartupLoader loader;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 台账仓储（{@code sanction_list_imports}）。非 final：由 {@link #setImportRepository} 注入，
     * 测试/无数据源场景保持 null（台账不可用，但名单变更与审计照常）。
     */
    private SanctionListImportRepository importRepository;

    /** 3 参构造器（既有 standalone MockMvc 测试按此装配）。 */
    public SanctionListController(InMemorySanctionListChecker checker,
                                   SanctionListStartupLoader loader,
                                   @Autowired(required = false) AuditLogService auditLogService) {
        this(checker, loader, auditLogService, null);
    }

    /**
     * 完整构造器（2026-10-12 新增台账仓储）。
     *
     * <p>刻意保留 3 参构造器并让它委托：加参数会重蹈"多构造器无 @Autowired ⇒ Spring
     * 选不中默认构造器"的坑（2026-10-11 网关侧 {@code No default constructor found} 事故）。
     * Spring 只把 {@code @Autowired(required=false)} 的那个当候选，另一条是普通重载。</p>
     */
    public SanctionListController(InMemorySanctionListChecker checker,
                                   SanctionListStartupLoader loader,
                                   AuditLogService auditLogService,
                                   SanctionListImportRepository importRepository) {
        this.checker = checker;
        this.loader = loader;
        this.auditLogService = auditLogService;
        this.importRepository = importRepository;
    }

    /** 台账仓储（可空：测试与无数据源场景不装配）。 */
    @Autowired(required = false)
    public void setImportRepository(SanctionListImportRepository importRepository) {
        this.importRepository = importRepository;
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
        audit("SANCTION_LIST_ENTRY_ADDED", "entry=" + entry + ", size=" + checker.size());
        return ResponseEntity.ok(Map.of("size", checker.size()));
    }

    /** 移除条目。 */
    @DeleteMapping
    public ResponseEntity<Map<String, Object>> remove(@RequestParam String entry) {
        if (entry == null || entry.isBlank()) {
            throw new IllegalArgumentException("entry 不能为空");
        }
        checker.removeEntry(entry);
        audit("SANCTION_LIST_ENTRY_REMOVED", "entry=" + entry + ", size=" + checker.size());
        return ResponseEntity.ok(Map.of("size", checker.size()));
    }

    /** 从配置的文件路径重新加载名单（追加语义，按格式自动识别）。 */
    @PostMapping("/reload")
    public ResponseEntity<Map<String, Object>> reload() {
        int loaded = loader.reload();
        audit("SANCTION_LIST_RELOADED", "loaded=" + loaded + ", size=" + checker.size());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("loaded", loaded);
        resp.put("size", checker.size());
        resp.put("empty", checker.isEmpty());
        return ResponseEntity.ok(resp);
    }

    /**
     * 导入离线名单包（2026-10-12 新增）。
     *
     * <p><b>两种投递方式</b>（面向"客户内网无外网"的交付形态，怎么方便怎么来）：</p>
     * <ul>
     *   <li><b>裸体直传</b>（推荐，运营最省事）：
     *       {@code curl --data-binary @sdn.csv "…/import?source=OFAC_SDN&version=2026-10-12"}</li>
     *   <li><b>JSON</b>：{@code {"source":"UN_CONSOLIDATED","version":"2026-10-12",
     *       "content":"…","mode":"append"}}（content 与 path 二选一）</li>
     *   <li><b>服务器本地文件</b>：JSON 里给 {@code path}（离线运维把包放到主机再导）</li>
     * </ul>
     *
     * <p><b>语义</b>：{@code mode=append}（默认，追加，与 reload 一致）或
     * {@code mode=replace}（**整体替换**，会抹掉人工补录条目，故必须显式指定）。
     * 解析出 0 条有效条目 → <b>400 且名单不变</b>（fail-closed：绝不"用空包把名单清空"）。</p>
     *
     * <p><b>留痕</b>：写审计事件（含操作人、来源、版本、条目数）+ 落一条版本台账。</p>
     */
    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importPack(
            @RequestBody(required = false) String body,
            @RequestHeader(value = "Content-Type", required = false) String contentType,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String version,
            @RequestParam(required = false) String mode) {
        return doImport(body, contentType, source, version, mode);
    }

    /** 导入实现（独立方法便于单测直接调用；端点方法只做签名绑定）。 */
    ResponseEntity<Map<String, Object>> doImport(String body, String contentType,
                                                 String source, String version, String mode) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("导入内容不能为空");
        }
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_IMPORT_BYTES) {
            throw new IllegalArgumentException("导入内容超过上限 " + (MAX_IMPORT_BYTES / 1024 / 1024) + "MB");
        }

        String content = body;
        String importMode = mode;
        // JSON 形态：从 body 里取 source/version/content/path/mode（显式 query 参数优先）
        if (contentType != null && contentType.toLowerCase().contains("json")) {
            JsonNode node;
            try {
                node = objectMapper.readTree(body);
            } catch (Exception e) {
                throw new IllegalArgumentException("JSON 解析失败: " + e.getMessage());
            }
            if (source == null) {
                source = text(node, "source");
            }
            if (version == null) {
                version = text(node, "version");
            }
            if (importMode == null) {
                importMode = text(node, "mode");
            }
            String inline = text(node, "content");
            String path = text(node, "path");
            if (inline != null) {
                content = inline;
            } else if (path != null) {
                content = readFile(path);
            } else {
                throw new IllegalArgumentException("JSON 形态需要 content 或 path 之一");
            }
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source 不能为空（如 OFAC_SDN / UN_CONSOLIDATED）");
        }
        String effectiveMode = (importMode == null || importMode.isBlank()) ? "append" : importMode.trim();
        if (!"append".equals(effectiveMode) && !"replace".equals(effectiveMode)) {
            throw new IllegalArgumentException("mode 只支持 append / replace");
        }

        SanctionListPackParser.Result parsed =
                SanctionListPackParser.parse(content, SanctionListPackParser.Format.AUTO);
        if (parsed.getCount() == 0) {
            // fail-closed：空解析结果绝不落到名单上（replace 模式下尤其危险）
            throw new IllegalArgumentException(
                    "名单包解析出 0 条有效条目，已拒绝导入（名单未变更）——请检查文件格式是否为"
                            + " OFAC SDN CSV / UN 综合名单 XML / 一行一条纯文本");
        }

        int imported;
        int removed = 0;
        if ("replace".equals(effectiveMode)) {
            removed = checker.replaceAll(parsed.getEntries());
            imported = parsed.getCount();
        } else {
            imported = checker.addEntries(parsed.getEntries());
        }

        String hash = sha256(content);
        String actor = currentActor();
        String detail = String.format(
                "source=%s, version=%s, format=%s, mode=%s, parsed=%d, added=%d, removed=%d, skipped=%d, total=%d, sha256=%s",
                source, version, parsed.getFormat(), effectiveMode, parsed.getCount(),
                imported, removed, parsed.getSkipped(), checker.size(), hash);
        audit("SANCTION_LIST_IMPORTED", detail);
        recordImport(actor, source, version, parsed, effectiveMode, imported, removed, hash);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("source", source);
        resp.put("version", version);
        resp.put("format", parsed.getFormat().name());
        resp.put("mode", effectiveMode);
        resp.put("parsed", parsed.getCount());
        resp.put("added", imported);
        resp.put("removed", removed);
        resp.put("skipped", parsed.getSkipped());
        resp.put("total", checker.size());
        resp.put("sha256", hash);
        resp.put("empty", checker.isEmpty());
        return ResponseEntity.ok(resp);
    }

    /** 名单版本台账（最近 20 条）：回答"当前生效的名单是哪个版本、谁导的"。 */
    @GetMapping("/imports")
    public ResponseEntity<Map<String, Object>> imports() {
        if (importRepository == null) {
            return ResponseEntity.ok(Map.of("available", false, "imports", List.of()));
        }
        SanctionListImportRepository repository = importRepository;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SanctionListImportEntity e :
                repository.findAllByOrderByImportedAtDesc(PageRequest.of(0, 20))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("importedAt", e.getImportedAt() == null ? null : e.getImportedAt().toString());
            row.put("actor", e.getActor());
            row.put("source", e.getSource());
            row.put("version", e.getVersion());
            row.put("format", e.getFormat());
            row.put("mode", e.getMode());
            row.put("parsed", e.getImportedCount());
            row.put("removed", e.getRemovedCount());
            row.put("totalAfter", e.getTotalAfter());
            row.put("sha256", e.getContentSha256());
            rows.add(row);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("available", true);
        resp.put("current", rows.isEmpty() ? null : rows.get(0));
        resp.put("imports", rows);
        return ResponseEntity.ok(resp);
    }

    // ------------------------------------------------------------- 内部工具

    private void recordImport(String actor, String source, String version,
                              SanctionListPackParser.Result parsed, String mode,
                              int imported, int removed, String hash) {
        if (importRepository == null) {
            return;   // 无数据源（单测/库形态）：台账不可用，但审计与名单变更照常
        }
        SanctionListImportRepository repository = importRepository;
        try {
            SanctionListImportEntity e = new SanctionListImportEntity();
            e.setActor(actor);
            e.setSource(source);
            e.setVersion(version);
            e.setFormat(parsed.getFormat().name());
            e.setMode(mode);
            e.setImportedCount(parsed.getCount());
            e.setSkippedCount(parsed.getSkipped());
            e.setRemovedCount(removed);
            e.setTotalAfter(checker.size());
            e.setContentSha256(hash);
            repository.save(e);
        } catch (RuntimeException ex) {
            // 台账写失败不阻断名单变更（与"审计失败不阻断"同口径），但必须可见
            org.slf4j.LoggerFactory.getLogger(SanctionListController.class)
                    .error("[SanctionList] 导入台账写入失败（名单已按导入结果生效）: source={}, version={}, error={}",
                            source, version, ex.getMessage());
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String readFile(String path) {
        try {
            return Files.readString(Path.of(path));
        } catch (Exception e) {
            throw new IllegalArgumentException("读取名单文件失败: " + path + " (" + e.getMessage() + ")");
        }
    }

    private static String sha256(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 当前操作人：取自 JWT 认证上下文；无认证（单测/内部调用）时回落 system。 */
    private static String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null
                || auth.getName().isBlank()) {
            return "system";
        }
        return "admin:" + auth.getName();
    }

    /**
     * 写审计（2026-10-12 修正参数被丢弃 + actor 恒 system 两个问题）。
     *
     * <p>直接用 {@link AuditLogService#record(AuditEvent)} 而不是 {@code recordAdjust}：
     * 后者把 actor 硬编码为 {@code system}、并把 detail 拼成 {@code amount=null}
     * （它本是给账务调账设计的）。制裁名单变更的审计必须携带**操作人 + 条目/版本明细**。</p>
     */
    private void audit(String action, String detail) {
        if (auditLogService == null) {
            return;
        }
        try {
            AuditEvent event = new AuditEvent();
            event.setActor(currentActor());
            event.setAction(action);
            event.setResource("sanction-list");
            event.setDetail(detail);
            auditLogService.record(event);
        } catch (RuntimeException ignored) {
            // 审计失败不影响合规数据变更（旁路口径，与资金路径一致）
        }
    }
}
