package org.nexus.compliance.reputation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 默认信誉评分服务实现。
 * <p>
 * 评分模型（0~100 分制，初始 60 分）：
 * <ul>
 *   <li>正向事件：PAYMENT_COMPLETED +1、SETTLEMENT_ON_TIME +2、KYC_UPGRADED +10</li>
 *   <li>负向事件：DISPUTE -10、RISK_BLOCKED -20、AML_HIGH_RISK -40</li>
 * </ul>
 * 等级映射：A ≥ 80，B ≥ 60，C ≥ 40，D &lt; 40。
 * 事件以 {@link ReputationEvent} 传入（亦兼容事件类型名字符串），
 * 每次更新记录历史并即时重算等级。当前为进程内存储。
 * </p>
 */
@Service
public class DefaultReputationService implements ReputationService {

    private static final Logger log = LoggerFactory.getLogger(DefaultReputationService.class);

    /** 初始分数 */
    private static final int INITIAL_SCORE = 60;

    /** 分数下界 */
    private static final int MIN_SCORE = 0;

    /** 分数上界 */
    private static final int MAX_SCORE = 100;

    /** 地址 → 当前分数 */
    private final Map<String, Integer> scores = new ConcurrentHashMap<>();

    /** 地址 → 历史事件描述（按时间顺序追加） */
    private final Map<String, List<String>> histories = new ConcurrentHashMap<>();

    @Override
    public ReputationScore getScore(String address) {
        ReputationScore score = new ReputationScore();
        score.setAddress(address);
        if (address == null || address.isBlank()) {
            score.setScore(0);
            score.setGrade(ReputationScore.Grade.D);
            return score;
        }
        int value = scores.getOrDefault(address, INITIAL_SCORE);
        score.setScore(value);
        score.setGrade(toGrade(value));
        score.setHistoryEvents(List.copyOf(histories.getOrDefault(address, List.of())));
        return score;
    }

    @Override
    public ReputationScore updateScore(String address, Object event) {
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("address is required");
        }
        ReputationEvent typed = toEvent(event);
        int delta = typed != null ? deltaOf(typed.getType()) : 0;

        int updated = clamp(scores.getOrDefault(address, INITIAL_SCORE) + delta);
        scores.put(address, updated);

        String historyEntry = String.format("[%s] %s delta=%+d score=%d (%s)",
                Instant.now(),
                typed != null ? describe(typed) : "UNKNOWN_EVENT",
                delta, updated, toGrade(updated));
        histories.computeIfAbsent(address, k -> new CopyOnWriteArrayList<>()).add(historyEntry);

        log.info("Reputation updated: address={}, delta={}, score={}, grade={}",
                address, delta, updated, toGrade(updated));
        return getScore(address);
    }

    @Override
    public List<String> getHistory(String address) {
        if (address == null || address.isBlank()) {
            return List.of();
        }
        // 按时间倒序返回
        List<String> history = histories.getOrDefault(address, List.of());
        List<String> reversed = new ArrayList<>(history);
        java.util.Collections.reverse(reversed);
        return reversed;
    }

    private ReputationEvent toEvent(Object event) {
        if (event instanceof ReputationEvent) {
            return (ReputationEvent) event;
        }
        if (event instanceof ReputationEvent.EventType) {
            return new ReputationEvent((ReputationEvent.EventType) event, null);
        }
        if (event instanceof String) {
            try {
                return new ReputationEvent(
                        ReputationEvent.EventType.valueOf((String) event), null);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    private int deltaOf(ReputationEvent.EventType type) {
        if (type == null) {
            return 0;
        }
        return switch (type) {
            case PAYMENT_COMPLETED -> 1;
            case SETTLEMENT_ON_TIME -> 2;
            case KYC_UPGRADED -> 10;
            case DISPUTE -> -10;
            case RISK_BLOCKED -> -20;
            case AML_HIGH_RISK -> -40;
        };
    }

    private String describe(ReputationEvent event) {
        return event.getDescription() != null && !event.getDescription().isBlank()
                ? event.getType() + ":" + event.getDescription()
                : event.getType().name();
    }

    private ReputationScore.Grade toGrade(int value) {
        if (value >= 80) {
            return ReputationScore.Grade.A;
        }
        if (value >= 60) {
            return ReputationScore.Grade.B;
        }
        if (value >= 40) {
            return ReputationScore.Grade.C;
        }
        return ReputationScore.Grade.D;
    }

    private int clamp(int value) {
        return Math.max(MIN_SCORE, Math.min(MAX_SCORE, value));
    }
}
