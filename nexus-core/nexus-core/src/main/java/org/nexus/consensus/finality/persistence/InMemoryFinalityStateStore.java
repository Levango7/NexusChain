package org.nexus.consensus.finality.persistence;

import org.nexus.consensus.finality.Vote;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版最终性状态存储（测试/单机，等价现有 {@code ConcurrentHashMap} 行为）。
 *
 * <p>PLAN-018：与 JDBC 版对齐 —— 投票连同签名一起保存（可恢复完整 Vote 对象），
 * 并提供 outbox 的内存实现，使 dev profile 与单测拥有与生产同构的重试语义。</p>
 */
public class InMemoryFinalityStateStore implements FinalityStateStore {

    private final Map<String, Set<String>> votes = new ConcurrentHashMap<>();
    private final Map<String, Boolean> finalized = new ConcurrentHashMap<>();
    /** 带签名的完整 Vote（key 与 votes 一致）。 */
    private final Map<String, List<Vote>> voteObjects = new ConcurrentHashMap<>();
    /** outbox：key = epoch|checkpoint|validator|origin。 */
    private final Map<String, FinalityOutboxEntry> outbox = new LinkedHashMap<>();

    @Override
    public void recordVote(long epoch, byte[] checkpointHash, String validatorAddress) {
        recordVote(epoch, checkpointHash, validatorAddress, null, null);
    }

    @Override
    public void recordVote(long epoch, byte[] checkpointHash, String validatorAddress,
                           byte[] signature, byte[] publicKey) {
        String key = key(epoch, checkpointHash);
        votes.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet())
                .add(validatorAddress);
        if (signature != null && signature.length > 0) {
            voteObjects.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(new Vote(epoch, checkpointHash, validatorAddress, signature, publicKey));
        }
    }

    @Override
    public Map<String, List<Vote>> loadAllVoteObjects() {
        Map<String, List<Vote>> copy = new LinkedHashMap<>();
        voteObjects.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        return copy;
    }

    @Override
    public void enqueuePendingVote(FinalityOutboxEntry entry) {
        outbox.put(outboxKey(entry), entry);
    }

    @Override
    public List<FinalityOutboxEntry> loadPendingVotes(int limit) {
        int cap = limit <= 0 ? 64 : limit;
        List<FinalityOutboxEntry> out = new ArrayList<>();
        for (FinalityOutboxEntry entry : outbox.values()) {
            if (out.size() >= cap) {
                break;
            }
            out.add(entry);
        }
        return out;
    }

    @Override
    public void removePendingVote(FinalityOutboxEntry entry) {
        outbox.remove(outboxKey(entry));
    }

    @Override
    public void markPendingVoteFailure(FinalityOutboxEntry entry, String error) {
        FinalityOutboxEntry current = outbox.get(outboxKey(entry));
        if (current == null) {
            return;
        }
        outbox.put(outboxKey(entry), new FinalityOutboxEntry(
                current.getEpoch(), current.getCheckpointHash(), current.getValidatorAddress(),
                current.getSignature(), current.getPublicKey(), current.getOrigin(),
                current.getCreatedAt(), current.getAttempts() + 1, error));
    }

    @Override
    public Map<String, List<Vote>> loadVoteObjectsSince(long fromEpoch) {
        Map<String, List<Vote>> out = new LinkedHashMap<>();
        voteObjects.forEach((key, votes) -> {
            if (epochOfKey(key) >= fromEpoch) {
                out.put(key, List.copyOf(votes));
            }
        });
        return out;
    }

    @Override
    public Set<String> loadFinalizedKeysSince(long fromEpoch) {
        Set<String> out = new LinkedHashSet<>();
        finalized.keySet().forEach(key -> {
            if (epochOfKey(key) >= fromEpoch) {
                out.add(key);
            }
        });
        return out;
    }

    private static long epochOfKey(String key) {
        int idx = key.indexOf('|');
        if (idx <= 0) {
            return 0L;
        }
        try {
            return Long.parseLong(key.substring(0, idx));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    @Override
    public void markFinalized(long epoch, byte[] checkpointHash) {
        finalized.put(key(epoch, checkpointHash), Boolean.TRUE);
    }

    @Override
    public boolean isFinalized(long epoch, byte[] checkpointHash) {
        return Boolean.TRUE.equals(finalized.get(key(epoch, checkpointHash)));
    }

    @Override
    public Set<String> loadVoters(long epoch, byte[] checkpointHash) {
        Set<String> v = votes.get(key(epoch, checkpointHash));
        return v == null ? ConcurrentHashMap.newKeySet() : Set.copyOf(v);
    }

    @Override
    public Map<String, Boolean> loadAllFinalized() {
        return Map.copyOf(finalized);
    }

    @Override
    public Map<String, Set<String>> loadAllVotes() {
        return Map.copyOf(votes);
    }

    static String key(long epoch, byte[] checkpointHash) {
        return epoch + "|" + HexFormat.of().formatHex(
                checkpointHash == null ? new byte[0] : checkpointHash);
    }

    private static String outboxKey(FinalityOutboxEntry entry) {
        return key(entry.getEpoch(), entry.getCheckpointHash())
                + "|" + entry.getValidatorAddress() + "|" + entry.getOrigin().code();
    }
}
