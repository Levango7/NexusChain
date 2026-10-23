package org.nexus.gateway.security.encryption;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.security.audit.SecurityAuditService;
import org.nexus.gateway.security.audit.SecurityMetricsRecorder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * KEK 两个 Gauge 是否真被喂（台账 #52）：{@code nexus_kek_version} 与
 * {@code nexus_key_rotation_progress}。
 *
 * <p>放在 {@code security.encryption} 包内是为了能直接调包私有的
 * {@link KeyRotationScheduler#progressPercent}——百分比口径（{@code total=0} 记 100、
 * 部分迁移向下取整）是这批里最容易悄悄错的一条，用公开路径间接验会把它和 mock 的
 * repository 行为混在一起。</p>
 *
 * <p>装配仍走真容器取 {@link ObjectProvider}，指标读的是 {@link SimpleMeterRegistry} 注册表，
 * 不是断"recorder 有这个方法"。</p>
 */
class KeyRotationMetricsWiringTest {

    private final List<AnnotationConfigApplicationContext> opened = new ArrayList<>();

    @AfterEach
    void closeContexts() {
        opened.forEach(AnnotationConfigApplicationContext::close);
        opened.clear();
    }

    @Test
    @DisplayName("版本 Gauge 既跟轮换走，也被每日检查按权威值刷新")
    void kekVersionGaugeTracksAuthorityNotOnlyRotations() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        KeyManagementService kms = mock(KeyManagementService.class);
        EncryptionKeyMetadataRepository metadataRepository = mock(EncryptionKeyMetadataRepository.class);
        KekRotationProgressRepository progressRepository = mock(KekRotationProgressRepository.class);

        when(kms.isAvailable()).thenReturn(true);
        when(kms.getCurrentKekVersion()).thenReturn(7);
        when(metadataRepository.countByKekVersionAndStatus(anyInt(), eq(KeyMetadataStatus.ACTIVE)))
                .thenReturn(0L);
        when(metadataRepository.findByKekVersionAndStatusAndIdGreaterThan(anyInt(),
                eq(KeyMetadataStatus.ACTIVE), anyLong(), any(PageRequest.class)))
                .thenReturn(Page.empty());
        when(progressRepository.findByOldVersion(anyInt())).thenReturn(Optional.empty());

        KeyRotationScheduler scheduler = scheduler(registry, kms, metadataRepository, progressRepository);

        assertEquals(0.0, gauge(registry, "nexus_kek_version"),
                "注册后没人写过时它就是 0——本类要消灭的正是这个形态");

        scheduler.checkKekRotationPeriod();
        assertEquals(7.0, gauge(registry, "nexus_kek_version"),
                "每日检查必须按 keyManagementService 的权威版本刷新，否则重启后面板拿 0 当版本号");

        when(kms.rotateKek()).thenReturn(8);
        scheduler.initiateRotation(7);
        assertEquals(8.0, gauge(registry, "nexus_kek_version"),
                "轮换当场推进去，不等下一次 cron（那是数天后）");
    }

    @Test
    @DisplayName("进度百分比口径：无待迁记录算完成，部分迁移向下取整")
    void progressPercentTreatsZeroTotalAsDone() {
        assertEquals(100, KeyRotationScheduler.progressPercent(null));
        assertEquals(100, KeyRotationScheduler.progressPercent(progress(0, 0)),
                "total=0 报 0 会让面板显示「轮换卡在第一格」，而实际什么都不用做");
        assertEquals(100, KeyRotationScheduler.progressPercent(progress(100, 100)));
        assertEquals(33, KeyRotationScheduler.progressPercent(progress(3, 1)),
                "还剩记录未迁时不许显示 100");
        assertEquals(99, KeyRotationScheduler.progressPercent(progress(100, 99)),
                "最后一条没迁完就是 99，不能四舍五入成 100");
    }

    @Test
    @DisplayName("进度写穿数据库的同时刷新进度 Gauge")
    void progressGaugeUpdatesWithEachPersist() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        KeyManagementService kms = mock(KeyManagementService.class);
        EncryptionKeyMetadataRepository metadataRepository = mock(EncryptionKeyMetadataRepository.class);
        KekRotationProgressRepository progressRepository = mock(KekRotationProgressRepository.class);

        when(kms.isAvailable()).thenReturn(true);
        when(kms.rotateKek()).thenReturn(2);
        when(kms.decryptDek(any(), anyInt())).thenReturn(new byte[]{9});
        when(kms.encryptDek(any(), anyInt())).thenReturn(new byte[]{10});
        when(metadataRepository.countByKekVersionAndStatus(anyInt(), eq(KeyMetadataStatus.ACTIVE)))
                .thenReturn(4L);
        // 第一批给 2 条、第二批空 ⇒ 2/4 = 50%
        Page<EncryptionKeyMetadata> firstBatch =
                new PageImpl<>(List.of(metadata(1L), metadata(2L)), PageRequest.of(0, 100), 2);
        when(metadataRepository.findByKekVersionAndStatusAndIdGreaterThan(anyInt(),
                eq(KeyMetadataStatus.ACTIVE), anyLong(), any(PageRequest.class)))
                .thenReturn(firstBatch)
                .thenReturn(Page.empty());
        when(progressRepository.findByOldVersion(anyInt())).thenReturn(Optional.empty());

        KeyRotationScheduler scheduler = scheduler(registry, kms, metadataRepository, progressRepository);
        scheduler.initiateRotation(1);

        assertEquals(50.0, gauge(registry, "nexus_key_rotation_progress"),
                "迁了 2/4 条 ⇒ 50；Gauge 从没被写过就停在 0，看不出迁移在推进");
    }

    // ==================== 辅助 ====================

    private KeyRotationScheduler scheduler(SimpleMeterRegistry registry,
                                           KeyManagementService kms,
                                           EncryptionKeyMetadataRepository metadataRepository,
                                           KekRotationProgressRepository progressRepository) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        SecurityMetricsRecorder recorder = new SecurityMetricsRecorder(registry);
        ctx.registerBean(SecurityMetricsRecorder.class, () -> recorder);
        ctx.refresh();
        opened.add(ctx);
        return new KeyRotationScheduler(kms, metadataRepository,
                mock(EncryptionConfigRepository.class), progressRepository,
                mock(SecurityAuditService.class), ctx.getBeanProvider(SecurityMetricsRecorder.class));
    }

    private EncryptionKeyMetadata metadata(long id) {
        EncryptionKeyMetadata m = new EncryptionKeyMetadata();
        m.setId(id);
        m.setEncryptedDek(new byte[]{1});
        return m;
    }

    private KeyRotationScheduler.MigrationProgress progress(int total, int migrated) {
        return new KeyRotationScheduler.MigrationProgress(1, 2, total, migrated, 0, 0L, migrated >= total);
    }

    private double gauge(SimpleMeterRegistry registry, String name) {
        return registry.get(name).gauge().value();
    }
}
