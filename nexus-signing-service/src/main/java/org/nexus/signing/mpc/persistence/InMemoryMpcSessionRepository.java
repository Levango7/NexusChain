package org.nexus.signing.mpc.persistence;

import org.nexus.signing.mpc.MpcSignSession;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link MpcSessionRepository} 的内存实现（composite build 占位 / 测试 fallback）。
 */
@Repository
@ConditionalOnMissingBean(name = "jpaMpcSessionRepository")
public class InMemoryMpcSessionRepository implements MpcSessionRepository {

    private final ConcurrentHashMap<String, MpcSignSession> store = new ConcurrentHashMap<>();

    @Override
    public MpcSignSession save(MpcSignSession session) {
        if (session.getSessionId() == null) {
            throw new IllegalArgumentException("sessionId must be set before save");
        }
        store.put(session.getSessionId(), session);
        return session;
    }

    @Override
    public Optional<MpcSignSession> findById(String sessionId) {
        return Optional.ofNullable(store.get(sessionId));
    }

    @Override
    public List<MpcSignSession> findByWalletId(String walletId) {
        List<MpcSignSession> result = new ArrayList<>();
        for (MpcSignSession s : store.values()) {
            if (walletId.equals(s.getWalletId())) {
                result.add(s);
            }
        }
        result.sort(Comparator.comparing(MpcSignSession::getCreatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return result;
    }

    @Override
    public List<MpcSignSession> findByStatus(MpcSignSession.SessionStatus status) {
        List<MpcSignSession> result = new ArrayList<>();
        for (MpcSignSession s : store.values()) {
            if (status.equals(s.getStatus())) {
                result.add(s);
            }
        }
        return result;
    }

    @Override
    public void deleteById(String sessionId) {
        store.remove(sessionId);
    }
}