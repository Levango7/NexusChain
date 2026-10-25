package org.nexus.bridge.repository;

import jakarta.persistence.LockModeType;
import org.nexus.bridge.safety.BridgeDailyUsageRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 日限额用量落库 Repository（PLAN-038）。
 *
 * <p>单行表 {@code bridge_daily_usage}（id 恒为 1）：读走主键查询；
 * 预留/归还走 {@link #findByIdForUpdate} 的行级悲观写锁——多副本并发时
 * 对同一行串行化，使日限额成为<b>全局</b>约束。</p>
 *
 * @since 2.76.0
 */
@Repository
public interface BridgeDailyUsageRepository extends JpaRepository<BridgeDailyUsageRecord, Long> {

    /**
     * 取单例行并加行级写锁（PESSIMISTIC_WRITE）。
     *
     * <p>必须在事务内调用（{@code JdbcDailyUsageStore} 的 REQUIRES_NEW
     * 事务）；无行时返回空（由调用方按冷启动插入处理）。</p>
     *
     * @param id 单例行主键（恒为 1）
     * @return 记录 Optional
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from BridgeDailyUsageRecord r where r.id = :id")
    Optional<BridgeDailyUsageRecord> findByIdForUpdate(@Param("id") Long id);
}
