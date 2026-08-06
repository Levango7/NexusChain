package org.nexus.wallet.signing.mpc.persistence;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link MpcContextRepository} 的内存实现（composite build 占位 / 测试 fallback）。
 *
 * <p>键为 {@code sessionId|round}，同一会话同一轮次只保留最新上下文
 * （多参与者场景下每个参与者有独立的进程与 Repository 实例）。</p>
 */
@Repository
@ConditionalOnMissingBean(name = "jpaMpcContextRepository")
public class InMemoryMpcContextRepository implements MpcContextRepository {

    private final ConcurrentHashMap<String, MpcProtocolContext> store = new ConcurrentHashMap<>();

    @Override
    public MpcProtocolContext save(MpcProtocolContext context) {
        if (context.getSessionId() == null) {
            throw new IllegalArgumentException("sessionId must be set before save");
        }
        String key = context.getSessionId() + "|" + context.getRound();
        store.put(key, context);
        return context;
    }

    @Override
    public Optional<MpcProtocolContext> findBySessionAndRound(String sessionId, int round) {
        return Optional.ofNullable(store.get(sessionId + "|" + round));
    }

    @Override
    public List<MpcProtocolContext> findBySessionId(String sessionId) {
        List<MpcProtocolContext> result = new ArrayList<>();
        for (MpcProtocolContext c : store.values()) {
            if (sessionId.equals(c.getSessionId())) {
                result.add(c);
            }
        }
        result.sort(Comparator.comparingInt(MpcProtocolContext::getRound));
        return result;
    }

    @Override
    public void deleteBySessionId(String sessionId) {
        store.entrySet().removeIf(e -> sessionId.equals(e.getValue().getSessionId()));
    }
}