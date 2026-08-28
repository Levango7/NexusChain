package org.nexus.signing.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 审批记录文件持久化存储。
 *
 * <p>将审批请求以 JSON Lines 格式持久化到文件系统，启动时恢复。
 * 每条审批记录一行 JSON，追加写入（append-only），无需数据库依赖。
 *
 * <p>存储路径：{@code data/approval-records.jsonl}（相对于工作目录）。
 * 通过 {@code nexus.approval.persistence.file-path} 配置可覆盖。
 *
 * <p>线程安全：使用 {@link ConcurrentHashMap} 内存缓存 + 同步文件写入。
 *
 * @since 2.15.0
 */
public class FileBasedApprovalStore implements ApprovalStore {

    private static final Logger log = LoggerFactory.getLogger(FileBasedApprovalStore.class);

    private final Path filePath;
    private final ObjectMapper mapper;
    private final Map<String, SigningApprovalRequest> store = new ConcurrentHashMap<>();

    public FileBasedApprovalStore(String filePathStr) {
        this(Paths.get(filePathStr != null && !filePathStr.isBlank() ? filePathStr : "data/approval-records.jsonl"));
    }

    public FileBasedApprovalStore(Path filePath) {
        this.filePath = filePath;
        this.mapper = new ObjectMapper();
        loadFromFile();
    }

    /** 从文件恢复审批记录 */
    private void loadFromFile() {
        if (!Files.exists(filePath)) {
            log.info("审批记录文件不存在，跳过恢复: {}", filePath);
            return;
        }
        int loaded = 0;
        int skipped = 0;
        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    ApprovalRecordDto dto = mapper.readValue(line, ApprovalRecordDto.class);
                    SigningApprovalRequest req = dto.toRequest();
                    store.put(req.getRequestId(), req);
                    loaded++;
                } catch (Exception e) {
                    skipped++;
                    log.warn("跳过损坏的审批记录行: {}", e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("读取审批记录文件失败: {}", e.getMessage(), e);
        }
        log.info("审批记录恢复完成: loaded={}, skipped={}, file={}", loaded, skipped, filePath);
    }

    @Override
    public void put(String requestId, SigningApprovalRequest request) {
        store.put(requestId, request);
        persistRecord(request);
    }

    @Override
    public SigningApprovalRequest get(String requestId) {
        return store.get(requestId);
    }

    @Override
    public SigningApprovalRequest remove(String requestId) {
        SigningApprovalRequest removed = store.remove(requestId);
        if (removed != null) {
            rewriteFile();
        }
        return removed;
    }

    @Override
    public void save(String requestId, SigningApprovalRequest request) {
        store.put(requestId, request);
        rewriteFile();
    }

    /**
     * 原子 CAS：内存缓存经 {@link ConcurrentHashMap#compute} 原子迁移（成功判定
     * 在 bin 锁内的 lambda 中完成），成功后全量重写文件持久化。文件写入失败不影响
     * 内存状态一致性（与既有 save 的容错语义一致）。
     */
    @Override
    public boolean compareAndTransition(String requestId, SigningApprovalRequest.Status expected,
                                        SigningApprovalRequest.Status to) {
        if (requestId == null) {
            return false;
        }
        boolean[] transitioned = {false};
        store.compute(requestId, (k, existing) -> {
            if (existing != null && existing.getStatus() == expected) {
                transitioned[0] = true;
                return existing.withStatus(to);
            }
            return existing;
        });
        if (transitioned[0]) {
            rewriteFile();
        }
        return transitioned[0];
    }

    @Override
    public Set<Map.Entry<String, SigningApprovalRequest>> entrySet() {
        return store.entrySet();
    }

    @Override
    public int size() {
        return store.size();
    }

    /** 追加一条记录到文件 */
    private void persistRecord(SigningApprovalRequest request) {
        try {
            Files.createDirectories(filePath.getParent());
            String json = mapper.writeValueAsString(ApprovalRecordDto.from(request));
            Files.write(filePath,
                    Collections.singletonList(json),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("持久化审批记录失败: requestId={}, error={}", request.getRequestId(), e.getMessage());
        }
    }

    /** 全量重写文件（用于更新/删除操作后） */
    private void rewriteFile() {
        try {
            Files.createDirectories(filePath.getParent());
            List<String> lines = new ArrayList<>(store.size());
            for (SigningApprovalRequest req : store.values()) {
                lines.add(mapper.writeValueAsString(ApprovalRecordDto.from(req)));
            }
            Files.write(filePath, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.debug("审批记录文件重写完成: {} 条, file={}", lines.size(), filePath);
        } catch (IOException e) {
            log.error("重写审批记录文件失败: {}", e.getMessage());
        }
    }
}