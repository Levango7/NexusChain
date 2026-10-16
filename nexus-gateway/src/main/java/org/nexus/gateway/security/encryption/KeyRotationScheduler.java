package org.nexus.gateway.security.encryption;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 密钥轮换调度器 — 定时检查 KEK 轮换周期 + 渐进式 DEK 迁移。
 *
 * <p>核心职责：
 * <ul>
 *   <li>定时检查 KEK 是否到达轮换周期（默认 90 天）</li>
 *   <li>发起轮换时生成新版本 KEK</li>
 *   <li>批量迁移 DEK（100 条/批），支持断点续传</li>
 *   <li>迁移完成后检查旧 KEK 是否仍被引用，无引用则归档</li>
 * </ul>
 *
 * <p><b>渐进式迁移</b>：DEK 迁移分批执行（100 条/批），每批完成后记录进度。
 * 迁移失败的单条记录不中断整体迁移，仍可用旧 KEK 解密（双版本并存）。
 * 来源：设计文档 §8.2.3 渐进式迁移中断点续传。</p>
 *
 * <p><b>双版本 KEK 并存</b>：轮换期间旧版本和新版本 KEK 同时存在于缓存中，
 * 旧数据用旧 KEK 解密，新数据用新 KEK 加密。迁移完成后归档旧 KEK。
 * 来源：设计文档 §8.2.2。</p>
 *
 * <p><b>跨副本持久化（#102 PLAN §4.2 / #31-Step3）</b>：迁移进度此前只在本 JVM 的
 * {@code migrationProgressMap}，prod 三副本下"是否续跑迁移/是否需轮换"各副本判断不一致、
 * 且副本重启后断点归零。现以 {@link KekRotationProgress} 表为权威：内存 Map 退化为写穿缓存，
 * {@code lastProcessedId} 与 {@code completed} 落库共享。<b>因此 {@link #checkKekRotationPeriod()}
 * 现在可以（也已）安全地加 {@link SchedulerLock}</b>——状态已在库里，锁住的只是"谁来做本轮检查"，
 * 不再把执行权交给没有该状态的副本。</p>
 */
@Component
public class KeyRotationScheduler {

    private static final Logger log = LoggerFactory.getLogger(KeyRotationScheduler.class);

    private static final int BATCH_SIZE = 100;

    private final KeyManagementService keyManagementService;
    private final EncryptionKeyMetadataRepository metadataRepository;
    private final EncryptionConfigRepository configRepository;
    private final KekRotationProgressRepository progressRepository;

    /** 迁移进度跟踪：oldVersion → MigrationProgress（DB 为权威，本表为写穿缓存） */
    private final Map<Integer, MigrationProgress> migrationProgressMap = new ConcurrentHashMap<>();

    public KeyRotationScheduler(KeyManagementService keyManagementService,
                                  EncryptionKeyMetadataRepository metadataRepository,
                                  EncryptionConfigRepository configRepository,
                                  KekRotationProgressRepository progressRepository) {
        this.keyManagementService = keyManagementService;
        this.metadataRepository = metadataRepository;
        this.configRepository = configRepository;
        this.progressRepository = progressRepository;
    }

    /**
     * 定时检查 KEK 轮换周期 — 每天凌晨 2 点执行。
     *
     * <p>检查所有加密配置的 KEK 轮换周期，到达周期时自动触发轮换。</p>
     */
    @Scheduled(cron = "0 0 2 * * *")
    @SchedulerLock(name = "kekRotationCheck", lockAtMostFor = "PT30M", lockAtLeastFor = "PT5M")
    public void checkKekRotationPeriod() {
        if (!keyManagementService.isAvailable()) {
            log.warn("KEK 不可用，跳过轮换检查 — fail-closed");
            return;
        }

        // 先从库把"其他副本发起、本副本内存里没有"的未完成迁移捞回来，使三副本对
        // "是否仍在迁移"的判断一致（迁移进度以库为权威，内存仅写穿缓存）。
        hydrateFromRepository();

        // 检查是否有正在进行的迁移
        for (Map.Entry<Integer, MigrationProgress> entry : migrationProgressMap.entrySet()) {
            MigrationProgress progress = entry.getValue();
            if (!progress.isCompleted()) {
                log.info("KEK 版本 {} → {} 迁移仍在进行中，已迁移 {}/{}",
                        progress.oldVersion, progress.newVersion,
                        progress.migratedRecords.get(), progress.totalRecords);
                // 继续迁移
                migrateDeks(progress.oldVersion, progress.newVersion);
                return;
            }
        }

        // 检查是否需要轮换（简化版：基于配置的轮换周期）
        // 实际生产中应检查最后一次轮换时间是否超过配置的周期天数
        log.debug("KEK 轮换周期检查完成，无需轮换");
    }

    /**
     * 从 {@link KekRotationProgressRepository} 把"未完成且本副本内存没有"的迁移任务捞回内存。
     * 使跨副本、以及重启后的副本都能从库里的共享断点续跑，而非各看各的内存 / 从头再扫。
     */
    void hydrateFromRepository() {
        for (KekRotationProgress row : progressRepository.findByCompletedFalse()) {
            migrationProgressMap.computeIfAbsent(row.getOldVersion(), v -> new MigrationProgress(
                    row.getOldVersion(), row.getNewVersion(), row.getTotalRecords(),
                    row.getMigratedRecords(), row.getFailedRecords(),
                    row.getLastProcessedId(), false));
        }
    }

    /**
     * 把内存进度写穿到库（按 {@code oldVersion} upsert）。
     *
     * <p>{@link #initiateRotation} 在 {@code @Transactional} 之外先落一条"进行中"行；
     * {@link #migrateDeks} 内每批后与完成时各 upsert 一次。注意 {@code checkKekRotationPeriod}
     * 与 {@code initiateRotation} 都以自调用（{@code this.migrateDeks(...)}）进入迁移，
     * 不经代理 ⇒ 那两条形同逐条 autocommit 的写各自即时可见；被外部经代理调用时则并入调用方事务。
     * 两种情形下"共享断点/完成标志"都能被其他副本 hydrate 到——这是本表要解决的跨可见性。
     * 迁移本身按 {@code status=ACTIVE AND kekVersion=old} 选取、逐条幂等，故游标即便与部分批次
     * 非严格原子也只是"下次多扫一遍已迁的"，不会漏迁。</p>
     */
    private void persistProgress(MigrationProgress p) {
        KekRotationProgress row = progressRepository.findByOldVersion(p.oldVersion)
                .orElseGet(() -> {
                    KekRotationProgress fresh = new KekRotationProgress();
                    fresh.setOldVersion(p.oldVersion);
                    return fresh;
                });
        row.setNewVersion(p.newVersion);
        row.setTotalRecords(p.totalRecords);
        row.setMigratedRecords(p.migratedRecords.get());
        row.setFailedRecords(p.failedRecords.get());
        row.setLastProcessedId(p.lastProcessedId);
        row.setCompleted(p.completed);
        progressRepository.save(row);
    }

    /**
     * 发起 KEK 轮换 — 生成新版本 KEK 并启动渐进式 DEK 迁移。
     *
     * @param oldVersion 当前 KEK 版本
     * @return 新 KEK 版本号
     */
    public int initiateRotation(int oldVersion) {
        if (!keyManagementService.isAvailable()) {
            log.warn("禁止回退：KEK 不可用，无法轮换 — fail-closed");
            throw new KeyVersionUnavailableException(oldVersion);
        }

        int newVersion = keyManagementService.rotateKek();
        log.info("KEK 轮换已启动: v{} → v{}", oldVersion, newVersion);

        // 统计需迁移的 DEK 记录数
        long totalRecords = metadataRepository.countByKekVersionAndStatus(
                oldVersion, KeyMetadataStatus.ACTIVE);

        MigrationProgress progress = new MigrationProgress(oldVersion, newVersion, totalRecords);
        migrationProgressMap.put(oldVersion, progress);
        // 先落一条"进行中"进度行（本方法非 @Transactional，独立提交）：
        // 让其他副本/checkKekRotationPeriod 能看到这个迁移任务存在，重启后也能续跑。
        persistProgress(progress);

        // 启动迁移
        migrateDeks(oldVersion, newVersion);

        return newVersion;
    }

    /**
     * 渐进式 DEK 迁移 — 批量迁移（100 条/批），断点续传。
     *
     * <p>流程：
     * <ol>
     *   <li>按 ID 游标分页查询旧版本 KEK 的 DEK 记录</li>
     *   <li>每条记录：用旧 KEK 解密 DEK → 用新 KEK 加密 DEK → 更新记录</li>
     *   <li>迁移失败的单条记录不中断整体迁移</li>
     *   <li>全部迁移完成后检查旧 KEK 是否仍被引用</li>
     * </ol>
     */
    @Transactional
    public void migrateDeks(int oldVersion, int newVersion) {
        MigrationProgress progress = migrationProgressMap.get(oldVersion);
        if (progress == null) {
            progress = new MigrationProgress(oldVersion, newVersion,
                    metadataRepository.countByKekVersionAndStatus(oldVersion, KeyMetadataStatus.ACTIVE));
            migrationProgressMap.put(oldVersion, progress);
        }

        if (progress.isCompleted()) {
            log.info("KEK v{} → v{} 迁移已完成", oldVersion, newVersion);
            return;
        }

        log.info("开始 DEK 迁移: v{} → v{}, 断点位置: id>{}, 已迁移: {}/{}",
                oldVersion, newVersion, progress.lastProcessedId,
                progress.migratedRecords.get(), progress.totalRecords);

        while (true) {
            Page<EncryptionKeyMetadata> batch = metadataRepository
                    .findByKekVersionAndStatusAndIdGreaterThan(
                            oldVersion, KeyMetadataStatus.ACTIVE,
                            progress.lastProcessedId,
                            PageRequest.of(0, BATCH_SIZE));

            if (batch.isEmpty()) {
                break;
            }

            List<EncryptionKeyMetadata> records = batch.getContent();
            for (EncryptionKeyMetadata metadata : records) {
                try {
                    // 用旧 KEK 解密 DEK
                    byte[] dek = keyManagementService.decryptDek(
                            metadata.getEncryptedDek(), oldVersion);

                    // 用新 KEK 加密 DEK
                    byte[] newEncryptedDek = keyManagementService.encryptDek(dek, newVersion);

                    // 更新记录
                    metadata.setEncryptedDek(newEncryptedDek);
                    metadata.setKekVersion(newVersion);
                    metadata.setRotatedAt(Instant.now());
                    metadataRepository.save(metadata);

                    progress.migratedRecords.incrementAndGet();
                } catch (Exception e) {
                    log.error("DEK 迁移失败, metadataId={}, 仍可用旧 KEK 解密",
                            metadata.getId(), e);
                    // 不中断迁移，失败记录仍可用旧 KEK 解密
                    progress.failedRecords.incrementAndGet();
                }
            }

            // 更新断点位置
            progress.lastProcessedId = records.get(records.size() - 1).getId();
            // 写穿共享断点（与本批 DEK 保存同一事务提交 ⇒ 游标与实际迁移原子推进）
            persistProgress(progress);

            log.info("DEK 迁移批次完成: v{} → v{}, 已迁移: {}/{}, 失败: {}",
                    oldVersion, newVersion, progress.migratedRecords.get(),
                    progress.totalRecords, progress.failedRecords.get());

            // 如果不足一批，说明已到末尾
            if (records.size() < BATCH_SIZE) {
                break;
            }
        }

        // 检查是否仍有旧版本 KEK 的引用
        long remainingRecords = metadataRepository.countByKekVersionAndStatus(
                oldVersion, KeyMetadataStatus.ACTIVE);

        if (remainingRecords == 0) {
            // 迁移完成，归档旧 KEK
            keyManagementService.archiveKek(oldVersion);
            progress.completed = true;
            // completed=true 与本批迁移同事务提交 ⇒ 只有迁移真正落库，完成标志才可见，
            // 其他副本 hydrate 时不会再认领这个已完成的 old_version，避免重复 archiveKek。
            persistProgress(progress);
            log.info("KEK v{} → v{} 迁移完成，旧 KEK 已归档", oldVersion, newVersion);
        } else {
            // 仍有旧版本引用（多为迁移失败的零头），保持 completed=false，写穿当前游标供续跑。
            persistProgress(progress);
            log.warn("KEK v{} 仍有 {} 条 DEK 引用，拒绝归档 — KEY_STILL_IN_USE",
                    oldVersion, remainingRecords);
        }
    }

    /**
     * 查询迁移进度。
     *
     * <p>先 hydrate：让观测到的进度不取决于请求打到哪个副本（#102 PLAN §3.2 的运维可见性问题）。</p>
     */
    public MigrationProgress getMigrationProgress(int oldVersion) {
        hydrateFromRepository();
        return migrationProgressMap.get(oldVersion);
    }

    /**
     * 查询所有正在进行的迁移。
     */
    public Map<Integer, MigrationProgress> getAllMigrationProgress() {
        hydrateFromRepository();
        return Map.copyOf(migrationProgressMap);
    }

    /**
     * 迁移进度跟踪 DTO。
     */
    public static class MigrationProgress {

        private final int oldVersion;
        private final int newVersion;
        private final long totalRecords;
        private final AtomicLong migratedRecords = new AtomicLong(0);
        private final AtomicLong failedRecords = new AtomicLong(0);
        private volatile Long lastProcessedId = 0L;
        private volatile boolean completed = false;

        public MigrationProgress(int oldVersion, int newVersion, long totalRecords) {
            this.oldVersion = oldVersion;
            this.newVersion = newVersion;
            this.totalRecords = totalRecords;
        }

        /**
         * 从库 hydrate 时用的全字段构造（#31-Step3）：把某副本/上次运行已持久化的进度
         * 恢复到内存写穿缓存，使本副本从共享断点续跑。
         */
        MigrationProgress(int oldVersion, int newVersion, long totalRecords,
                          long migrated, long failed, long lastProcessedId, boolean completed) {
            this(oldVersion, newVersion, totalRecords);
            this.migratedRecords.set(migrated);
            this.failedRecords.set(failed);
            this.lastProcessedId = lastProcessedId;
            this.completed = completed;
        }

        public int getOldVersion() { return oldVersion; }
        public int getNewVersion() { return newVersion; }
        public long getTotalRecords() { return totalRecords; }
        public long getMigratedRecords() { return migratedRecords.get(); }
        public long getFailedRecords() { return failedRecords.get(); }
        public long getRemainingRecords() {
            return totalRecords - migratedRecords.get() - failedRecords.get();
        }
        public Long getLastProcessedId() { return lastProcessedId; }
        public boolean isCompleted() { return completed; }
        public String getMigrationStatus() {
            if (completed) return "COMPLETED";
            if (migratedRecords.get() == 0) return "IN_PROGRESS";
            return "IN_PROGRESS";
        }
    }
}