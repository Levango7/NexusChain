package org.nexus.consensus.finality.persistence;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版最终性状态存储（测试/单机，等价现有 {@code ConcurrentHashMap} 行为）。
 */
public class InMemoryFinalityStateStore implements FinalityStateStore {

    private final Map<String, Set<String>> votes = new ConcurrentHashMap<>();
    private final Map<String, Boolean> finalized = new ConcurrentHashMap<>();

    @Override
    public void recordVote(long epoch, byte[] checkpointHash, String validatorAddress) {
        votes.computeIfAbsent(key(epoch, checkpointHash), k -> ConcurrentHashMap.newKeySet())
                .add(validatorAddress);
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
        return epoch + "|" + Arrays.toString(checkpointHash);
    }
}