package org.nexus.compliance.identity;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存链上 DID 存储实现（测试/开发环境用）。
 *
 * <p>用 {@link ConcurrentHashMap} 模拟链上存储，验证 ChainDidService 的
 * 链上注册/解析/更新/吊销逻辑。生产环境替换为真实链上合约实现。
 *
 * @since 2.12.0
 */
public class InMemoryChainDidStore implements ChainDidStore {

    private final ConcurrentHashMap<String, DidDocument> documents = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> revoked = new ConcurrentHashMap<>();

    @Override
    public void register(String did, DidDocument document) {
        documents.put(did, document);
    }

    @Override
    public DidDocument resolve(String did) {
        return documents.get(did);
    }

    @Override
    public void update(String did, DidDocument document) {
        documents.put(did, document);
    }

    @Override
    public void revoke(String did) {
        revoked.put(did, true);
    }

    @Override
    public boolean exists(String did) {
        return documents.containsKey(did);
    }

    @Override
    public boolean isRevoked(String did) {
        return revoked.getOrDefault(did, false);
    }
}