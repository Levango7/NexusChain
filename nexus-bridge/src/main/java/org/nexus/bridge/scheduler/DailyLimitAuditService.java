package org.nexus.bridge.scheduler;

import org.nexus.bridge.BridgeService;
import org.nexus.bridge.BridgeStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Daily bridge limit auditor — snapshots and resets the 24-hour usage counter.
 *
 * <p>Runs every midnight (default) via @Scheduled.  Before resetting the
 * daily counter the service records a snapshot in the in-memory history map
 * so that bridge operators can audit yesterday's throughput.</p>
 */
@Component
public class DailyLimitAuditService {

    private static final Logger log = LoggerFactory.getLogger(DailyLimitAuditService.class);

    private final BridgeService bridgeService;

    /** Historical snapshots: date string → daily-used amount (NEX smallest unit). */
    private final Map<String, Long> dailyHistory = new ConcurrentHashMap<>();

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

        dailyHistory.put(yesterday, status.getDailyUsed());
        log.info("Daily bridge limit snapshot: {} → {} NEX (remaining: {} / {})",
                yesterday, status.getDailyUsed(), status.getDailyRemaining(), status.getDailyLimit());

        // The BridgeServiceImpl resetDailyIfNeeded() will zero the counter
        // on the next getStatus() call after midnight.
    }

    /** Query yesterday's usage. */
    public Long getYesterdayUsed() {
        String yesterday = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1).toString();
        return dailyHistory.get(yesterday);
    }

    /** Query usage for a specific date. */
    public Long getUsageForDate(String dateKey) { return dailyHistory.get(dateKey); }

    /** Full audit history (read-only). */
    public Map<String, Long> getFullHistory() { return Map.copyOf(dailyHistory); }
}
