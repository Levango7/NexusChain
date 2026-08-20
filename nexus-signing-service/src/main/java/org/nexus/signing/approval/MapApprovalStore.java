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

    @Override
    public Set<Map.Entry<String, SigningApprovalRequest>> entrySet() {
        return store.entrySet();
    }

    @Override
    public int size() {
        return store.size();
    }
}