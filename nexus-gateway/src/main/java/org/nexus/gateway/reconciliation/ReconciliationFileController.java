package org.nexus.gateway.reconciliation;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 对账文件 REST API。
 *
 * <p>提供日度/月度对账文件生成、文件历史查询、文件详情查看和文件下载端点。
 * 所有端点要求 MERCHANT 或 ADMIN 角色权限。</p>
 */
@RestController
@RequestMapping("/api/v1/reconciliation")
@PreAuthorize("hasRole('MERCHANT') or hasRole('ADMIN')")
public class ReconciliationFileController {

    private final ReconciliationFileService reconciliationFileService;

    public ReconciliationFileController(ReconciliationFileService reconciliationFileService) {
        this.reconciliationFileService = reconciliationFileService;
    }

    /**
     * 生成日对账文件。
     *
     * @param merchantId 商户 ID
     * @param date       对账日期（可选，默认今天）
     * @return 生成的文件记录
     */
    @PostMapping("/daily/{merchantId}")
    public ResponseEntity<ReconciliationFileRecord> generateDailyFile(
            @PathVariable Long merchantId,
            @RequestParam(required = false) LocalDate date) {
        if (date == null) {
            date = LocalDate.now();
        }
        ReconciliationFileRecord record = reconciliationFileService.generateDailyFile(merchantId, date);
        return ResponseEntity.ok(record);
    }

    /**
     * 生成月对账文件。
     *
     * @param merchantId 商户 ID
     * @param year       年份（可选，默认今年）
     * @param month      月份（可选，默认今月）
     * @return 生成的文件记录
     */
    @PostMapping("/monthly/{merchantId}")
    public ResponseEntity<ReconciliationFileRecord> generateMonthlyFile(
            @PathVariable Long merchantId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        if (year == null) {
            year = LocalDate.now().getYear();
        }
        if (month == null) {
            month = LocalDate.now().getMonthValue();
        }
        ReconciliationFileRecord record = reconciliationFileService.generateMonthlyFile(merchantId, year, month);
        return ResponseEntity.ok(record);
    }

    /**
     * 获取商户的对账文件列表。
     *
     * @param merchantId 商户 ID
     * @return 文件记录列表
     */
    @GetMapping("/files/{merchantId}")
    public ResponseEntity<List<ReconciliationFileRecord>> getFileHistory(
            @PathVariable Long merchantId) {
        List<ReconciliationFileRecord> records = reconciliationFileService.getFileHistory(merchantId);
        return ResponseEntity.ok(records);
    }

    /**
     * 获取特定文件记录详情。
     *
     * @param merchantId 商户 ID
     * @param fileId     文件记录 ID
     * @return 文件记录详情
     */
    @GetMapping("/files/{merchantId}/{fileId}")
    public ResponseEntity<ReconciliationFileRecord> getFileRecord(
            @PathVariable Long merchantId,
            @PathVariable Long fileId) {
        Optional<ReconciliationFileRecord> record = reconciliationFileService.getFileRecord(fileId);
        return record.map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 下载对账文件内容。
     *
     * <p>根据文件记录的 fileType 字段返回 CSV 或 JSON 格式内容。
     * 文件内容在下载时重新生成，确保数据始终最新。</p>
     *
     * @param fileId 文件记录 ID
     * @return 文件内容（CSV 或 JSON）
     */
    @GetMapping("/download/{fileId}")
    public ResponseEntity<String> downloadFile(@PathVariable Long fileId) {
        Optional<ReconciliationFileRecord> recordOpt = reconciliationFileService.getFileRecord(fileId);
        if (recordOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        ReconciliationFileRecord record = recordOpt.get();
        String content = reconciliationFileService.getFileContent(record);

        HttpHeaders headers = new HttpHeaders();
        String filename = "reconciliation_" + record.getPeriodType().toLowerCase()
                + "_" + record.getMerchantId()
                + "_" + record.getPeriodStart();

        if ("JSON".equalsIgnoreCase(record.getFileType())) {
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + filename + ".json\"");
        } else {
            headers.setContentType(MediaType.parseMediaType("text/csv"));
            headers.set(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + filename + ".csv\"");
        }

        return ResponseEntity.ok().headers(headers).body(content);
    }
}