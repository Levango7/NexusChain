package org.nexus.gateway.security.encryption;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * KEK 轮换迁移进度仓储（#31-Step3）。
 *
 * <p>供 {@link KeyRotationScheduler} 把迁移进度持久化为跨副本共享断点：
 * 按 {@code oldVersion} upsert、按 {@code completed=false} 查出未完成任务以在其他副本重启后续跑。</p>
 */
@Repository
public interface KekRotationProgressRepository extends JpaRepository<KekRotationProgress, Long> {

    Optional<KekRotationProgress> findByOldVersion(int oldVersion);

    List<KekRotationProgress> findByCompletedFalse();
}
