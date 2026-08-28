package org.nexus.signing.approval;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存审批存储（ConcurrentHashMap 适配器）。
 *
 * <p>将 {@link ConcurrentHashMap} 适配为 {@link ApprovalStore} 接口，
 * 用于单实例部署（默认实现）。
 *
 * @since 2.15.0
 */
public class MapApprovalStore implements ApprovalStore {

    private final Map<String, SigningApprovalRequest> store;

    public MapApprovalStore(Map<String, SigningApprovalRequest> store) {
        this.store = store;
    }

    @Override
    public void put(String requestId, SigningApprovalRequest request) {
        store.put(requestId, request);
    }

    @Override
    public SigningApprovalRequest get(String requestId) {
        return store.get(requestId);
    }

    @Override
    public SigningApprovalRequest remove(String requestId) {
        return store.remove(requestId);
    }

    @Override
    public void save(String requestId, SigningApprovalRequest request) {
        store.put(requestId, request);
    }

    /**
     * 原子 CAS：基于 {@link ConcurrentHashMap#compute} 的 per-key 原子操作，
     * 同一 requestId 的并发迁移严格串行化，杜绝 check-then-act 竞态。
     *
     * <p>成功判定必须在 lambda 内部完成（bin 锁内）："当前状态等于 expected"
     * 才算本次迁移成功。不能以 compute 返回值的状态 == to 判定——后来者的
     * 返回值是未变更的 already-transitioned 实例，会被误判为成功。
     *
     * <p>前提：构造方必须传入并发 Map（当前所有构造点均为
     * {@code new ConcurrentHashMap<>()}）。若传入非线程安全 Map，
     * compute 的原子性不成立——构造方需自行保证。</p>
     */
    @Override
    public boolean compareAndTransition(String requestId, SigningApprovalRequest.Status expected,
                                        SigningApprovalRequest.Status to) {
        if (requestId == null) {
            return false;
        }
        boolean[] transitioned = {false};
        store.compute(requestId, (k, existing) -> {
            if (existing != null && existing.getStatus() == expected) {
                transitioned[0] = true;
                return existing.withStatus(to);
            }
            return existing;
        });
        return transitioned[0];
    }

    @Override
    public Set<Map.Entry<String, SigningApprovalRequest>> entrySet() {
        return store.entrySet();
    }

    @Override
    public int size() {
        return store.size();
    }
}