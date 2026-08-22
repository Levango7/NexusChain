package org.nexus.core.persist;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 链上状态快照持久化工具。
 *
 * <p>为 {@link org.nexus.core.contract.ContractStorage}、
 * {@link org.nexus.consensus.pos.ValidatorRegistry}、
 * {@link org.nexus.consensus.pos.StakingServiceImpl} 等内存型组件
 * 提供 JSON 文件快照的加载 / 保存能力，解决重启后状态丢失问题。</p>
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li>加载失败或文件不存在时返回 {@code null}，调用方继续以空内存启动——<b>不阻塞启动</b>。</li>
 *   <li>保存失败仅告警——<b>不阻塞关闭</b>。</li>
 *   <li>写入采用「临时文件 + 原子 rename」策略，避免写一半进程被 kill 导致快照损坏。</li>
 *   <li>通过 {@code nexus.chain.state-persist.enabled} 配置开关可全局禁用（测试场景）。</li>
 * </ul>
 *
 * <h3>配置项</h3>
 * <ul>
 *   <li>{@code nexus.chain.state-dir}：快照目录，默认 {@code ./data/}。</li>
 *   <li>{@code nexus.chain.state-persist.enabled}：是否启用持久化，默认 {@code true}。</li>
 * </ul>
 *
 * @since 1.6
 */
@Component
public class StateSnapshotPersister {

    private static final Logger logger = LoggerFactory.getLogger(StateSnapshotPersister.class);

    @Value("${nexus.chain.state-dir:./data/}")
    private String stateDir;

    @Value("${nexus.chain.state-persist.enabled:true}")
    private boolean enabled;

    private final ObjectMapper mapper = new ObjectMapper()

            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    @PostConstruct
    void init() {
        // 构造阶段不创建目录，避免禁用持久化时产生副作用目录；
        // 目录在首次 save() 时按需创建。
        if (enabled) {
            logger.info("State snapshot persistence enabled, dir={}", Paths.get(stateDir).toAbsolutePath());
        } else {
            logger.info("State snapshot persistence disabled by config (nexus.chain.state-persist.enabled=false)");
        }
    }

    /**
     * 持久化是否启用。
     *
     * @return 启用返回 true
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 从快照文件加载状态。
     *
     * <p>文件不存在时返回 {@code null}（INFO 日志，视为首次启动）；
     * 解析失败时返回 {@code null}（WARN 日志，调用方以空状态继续）。</p>
     *
     * @param filename 快照文件名（相对于 state-dir）
     * @param typeRef  Jackson 类型引用（用于泛型反序列化）
     * @param <T>      目标类型
     * @return 反序列化对象；文件不存在或加载失败返回 {@code null}
     */
    public <T> T load(String filename, TypeReference<T> typeRef) {
        if (!enabled) {
            return null;
        }
        Path path = resolvePath(filename);
        if (!Files.exists(path)) {
            logger.info("Snapshot file not found, starting empty: {}", path);
            return null;
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            T result = mapper.readValue(json, typeRef);
            logger.info("Snapshot loaded: {}", path);
            return result;
        } catch (RuntimeException | java.io.IOException e) {
            logger.warn("Failed to load snapshot from {}, starting empty: {}", path, e.getMessage());
            return null;
        }
    }

    /**
     * 保存状态到快照文件。
     *
     * <p>采用「写入 .tmp → 原子 rename」策略保证一致性。
     * 保存失败仅告警，不抛出异常——不阻塞关闭流程。</p>
     *
     * @param filename 快照文件名（相对于 state-dir）
     * @param data     待序列化对象
     */
    public void save(String filename, Object data) {
        if (!enabled) {
            return;
        }
        Path path = resolvePath(filename);
        Path tmp = path.resolveSibling(filename + ".tmp");
        try {
            Path dir = path.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                // 跨文件系统（如 Windows 不同盘符）不支持原子移动，降级为非原子 replace
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            logger.info("Snapshot saved: {}", path);
        } catch (RuntimeException | java.io.IOException e) {
            logger.warn("Failed to save snapshot to {}: {}", path, e.getMessage());
            // 清理可能残留的临时文件
            try {
                if (Files.exists(tmp)) {
                    Files.deleteIfExists(tmp);
                }
            } catch (RuntimeException | java.io.IOException ignored) {
                // best-effort cleanup
            }
        }
    }

    private Path resolvePath(String filename) {
        return Paths.get(stateDir, filename);
    }
}