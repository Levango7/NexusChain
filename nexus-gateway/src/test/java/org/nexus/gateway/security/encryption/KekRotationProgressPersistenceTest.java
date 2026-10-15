package org.nexus.gateway.security.encryption;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KEK 轮换进度持久化 + 最后补锁（#31-Step3，#102 PLAN §4.2）。
 *
 * <p>三件事一起钉：① 迁移进度以 {@link KekRotationProgress} 表为权威，内存 Map 退化为写穿缓存
 * ⇒ 一个副本发起的迁移，<b>另一个副本/重启后的副本</b>能从库里 hydrate 出共享断点续跑；
 * ② {@code checkKekRotationPeriod} 在状态持久化<b>之后</b>才补 {@code @SchedulerLock}（PLAN 明令顺序不能反）；
 * ③ 迁移完成时 {@code completed} 标志落库 ⇒ 其他副本不再重复认领、避免重复 archiveKek。</p>
 */
@ExtendWith(MockitoExtension.class)
class KekRotationProgressPersistenceTest {

    @Mock private KeyManagementService keyManagementService;
    @Mock private EncryptionKeyMetadataRepository metadataRepository;
    @Mock private EncryptionConfigRepository configRepository;
    @Mock private KekRotationProgressRepository progressRepository;

    private KeyRotationScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new KeyRotationScheduler(
                keyManagementService, metadataRepository, configRepository, progressRepository);
    }

    private static KekRotationProgress row(int oldV, int newV, long lastId, boolean completed) {
        KekRotationProgress r = new KekRotationProgress();
        r.setOldVersion(oldV);
        r.setNewVersion(newV);
        r.setTotalRecords(500);
        r.setMigratedRecords(lastId);
        r.setFailedRecords(0);
        r.setLastProcessedId(lastId);
        r.setCompleted(completed);
        return r;
    }

    @Test
    @DisplayName("checkKekRotationPeriod 在状态已持久化后必须带 @SchedulerLock")
    void checkKekRotationPeriodMustCarrySchedulerLock() throws NoSuchMethodException {
        Method m = KeyRotationScheduler.class.getMethod("checkKekRotationPeriod");
        SchedulerLock lock = m.getAnnotation(SchedulerLock.class);
        assertNotNull(lock,
                "进度已落库（V99 kek_rotation_progress），checkKekRotationPeriod 现在应带 @SchedulerLock —— "
                        + "锁住的是'谁来做本轮检查'，状态在库里共享，不再怕把执行权交给没该状态的副本");
        assertFalse(lock.lockAtMostFor().isEmpty(), "必须显式 lockAtMostFor");
        assertFalse(lock.lockAtLeastFor().isEmpty(), "必须显式 lockAtLeastFor");
    }

    @Test
    @DisplayName("跨副本 hydrate：本副本内存没有、但库里有未完成迁移 → getMigrationProgress 从库读回共享断点")
    void hydratesUnfinishedMigrationFromRepository() {
        // 本副本全新、内存 Map 空；库里有另一副本发起的 oldVersion=5 迁移，断点在 id>300。
        when(progressRepository.findByCompletedFalse()).thenReturn(List.of(row(5, 6, 300L, false)));

        KeyRotationScheduler.MigrationProgress p = scheduler.getMigrationProgress(5);

        assertNotNull(p, "应能从库 hydrate 出其他副本发起的迁移");
        assertEquals(6, p.getNewVersion());
        assertEquals(300L, p.getLastProcessedId(), "断点必须来自库（共享），不是从头再扫的 0");
        assertEquals(300L, p.getMigratedRecords());
        assertFalse(p.isCompleted());
    }

    @Test
    @DisplayName("已完成的迁移不被 hydrate 回来（findByCompletedFalse 已排除），避免重复续跑/归档")
    void completedMigrationIsNotHydrated() {
        when(progressRepository.findByCompletedFalse()).thenReturn(List.of()); // 完成的不在其中

        assertNull(scheduler.getMigrationProgress(5));
    }

    @Test
    @DisplayName("迁移完成 → completed=true 写穿到库（供其他副本识别、避免重复 archiveKek）")
    void completionPersistsCompletedFlag() {
        when(keyManagementService.isAvailable()).thenReturn(true);
        when(keyManagementService.rotateKek()).thenReturn(6);
        when(metadataRepository.countByKekVersionAndStatus(5, KeyMetadataStatus.ACTIVE)).thenReturn(0L);
        when(metadataRepository.findByKekVersionAndStatusAndIdGreaterThan(
                anyInt(), any(), any(), any())).thenReturn(Page.empty());
        when(progressRepository.findByOldVersion(5)).thenReturn(java.util.Optional.empty());

        scheduler.initiateRotation(5);

        ArgumentCaptor<KekRotationProgress> cap = ArgumentCaptor.forClass(KekRotationProgress.class);
        verify(progressRepository, atLeastOnce()).save(cap.capture());
        KekRotationProgress last = cap.getAllValues().get(cap.getAllValues().size() - 1);
        assertTrue(last.isCompleted(), "无剩余引用 ⇒ 迁移完成，completed 必须落库=true");
        assertEquals(6, last.getNewVersion());
        verify(keyManagementService).archiveKek(5);
    }

    @Test
    @DisplayName("KEK 不可用时 checkKekRotationPeriod fail-closed：不动库不迁移")
    void checkSkipsWhenKeyUnavailable() throws Exception {
        when(keyManagementService.isAvailable()).thenReturn(false);

        scheduler.checkKekRotationPeriod();

        verify(progressRepository, never()).findByCompletedFalse();
        verify(metadataRepository, never()).findByKekVersionAndStatusAndIdGreaterThan(anyInt(), any(), any(), any());
    }
}
