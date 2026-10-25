package org.nexus.bridge.scheduler;

import org.nexus.bridge.BridgeService;
import org.nexus.bridge.BridgeStatus;
import org.nexus.bridge.repository.BridgeDailyLimitSnapshotRepository;
import org.nexus.bridge.safety.BridgeDailyLimitSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Daily bridge limit auditor — snapshots and resets the 24-hour usage counter.
 *
 * <p>Runs every midnight (default) via @Scheduled.  Before resetting the
 * daily counter the service records a snapshot so that bridge operators can
 * audit yesterday's throughput.</p>
 *
 * <p><b>PLAN-038</b>：快照落库（{@code bridge_daily_limit_snapshot} 表，
 * 经 {@link BridgeDailyLimitSnapshotRepository} 可选注入）——重启后历史不丢；
 * 仓库不可用（单测/嵌入式）时回落进程内 map，行为与历史一致。日期键主键
 * 去重：多副本午夜重复触发为幂等覆盖，无副作用。</p>
 */
@Component
public class DailyLimitAuditService {

    private static final Logger log = LoggerFactory.getLogger(DailyLimitAuditService.class);

    private final BridgeService bridgeService;

    /** 进程内快照缓存/降级：date string → daily-used amount (NEX smallest unit). */
    private final Map<String, Long> dailyHistory = new ConcurrentHashMap<>();

    /**
     * 快照落库仓库（PLAN-038，可空）：{@code null} = 单测/嵌入式，仅进程内历史。
     */
    @Autowired(required = false)
    private BridgeDailyLimitSnapshotRepository snapshotRepository;

    public DailyLimitAuditService(BridgeService bridgeService) {
        this.bridgeService = bridgeService;
    }

    /**
     * Midnight reset — snapshot and zero the daily counter.
     */
    @Scheduled(cron = "0 0 0 * * ?")
    public void dailyReset() {
        BridgeStatus status = bridgeService.getStatus();
        String yesterday = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1).toString();
        long used = status.getDailyUsed();

        dailyHistory.put(yesterday, used);
        persistSnapshot(yesterday, used);
        log.info("Daily bridge limit snapshot: {} → {} NEX (remaining: {} / {})",
                yesterday, used, status.getDailyRemaining(), status.getDailyLimit());

        // The daily usage store rolls the window lazily on the next reserve
        // (memory/jpa 两档一致；见 DailyUsageStore）。
    }

    /** Query yesterday's usage. */
    public Long getYesterdayUsed() {
        String yesterday = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1).toString();
        return getUsageForDate(yesterday);
    }

    /** Query usage for a specific date. */
    public Long getUsageForDate(String dateKey) {
        if (snapshotRepository != null) {
            try {
                return snapshotRepository.findById(dateKey)
                        .map(BridgeDailyLimitSnapshot::getUsed)
                        .orElse(dailyHistory.get(dateKey));
            } catch (RuntimeException e) {
                log.warn("snapshot read failed, falling back to in-memory: {}", e.getMessage());
            }
        }
        return dailyHistory.get(dateKey);
    }

    /** Full audit history (read-only). */
    public Map<String, Long> getFullHistory() {
        if (snapshotRepository != null) {
            try {
                Map<String, Long> out = new LinkedHashMap<>();
                for (BridgeDailyLimitSnapshot snapshot : snapshotRepository.findAllByOrderByDateKeyAsc()) {
                    out.put(snapshot.getDateKey(), snapshot.getUsed());
                }
                return out;
            } catch (RuntimeException e) {
                log.warn("snapshot list failed, falling back to in-memory: {}", e.getMessage());
            }
        }
        return Map.copyOf(dailyHistory);
    }

    /**
     * 落库快照（仓库可用时；同日覆盖为幂等，多副本无副作用）。
     */
    private void persistSnapshot(String dateKey, long used) {
        if (snapshotRepository == null) {
            return;
        }
        try {
            snapshotRepository.save(new BridgeDailyLimitSnapshot(dateKey, used, Instant.now()));
        } catch (RuntimeException e) {
            log.warn("snapshot persist failed (in-memory history only): {}", e.getMessage());
        }
    }
}
