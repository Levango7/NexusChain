package org.nexus.wallet.signing.mpc.persistence;

import org.nexus.wallet.signing.mpc.MpcWallet;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link MpcWalletRepository} 的内存实现（composite build 占位 / 测试 fallback）。
 *
 * <p>用 {@link ConcurrentHashMap} 模拟关系库表。{@code @ConditionalOnMissingBean}
 * 保证当未来引入 JPA 实现时，该 bean 自动让位。</p>
 */
@Repository
@ConditionalOnMissingBean(name = "jpaMpcWalletRepository")
public class InMemoryMpcWalletRepository implements MpcWalletRepository {

    private final ConcurrentHashMap<String, MpcWallet> store = new ConcurrentHashMap<>();

    @Override
    public MpcWallet save(MpcWallet wallet) {
        if (wallet.getWalletId() == null) {
            throw new IllegalArgumentException("walletId must be set before save");
        }
        store.put(wallet.getWalletId(), wallet);
        return wallet;
    }

    @Override
    public Optional<MpcWallet> findById(String walletId) {
        return Optional.ofNullable(store.get(walletId));
    }

    @Override
    public List<MpcWallet> findAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public List<MpcWallet> findByParticipant(String participantId) {
        List<MpcWallet> result = new ArrayList<>();
        for (MpcWallet w : store.values()) {
            if (w.getParticipants() != null && w.getParticipants().contains(participantId)) {
                result.add(w);
            }
        }
        return result;
    }

    @Override
    public void deleteById(String walletId) {
        store.remove(walletId);
    }

    @Override
    public boolean existsById(String walletId) {
        return store.containsKey(walletId);
    }
}