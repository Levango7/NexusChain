package org.nexus.compliance.identity;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存链上 DID 存储实现（测试/开发环境用）。
 *
 * <p>用 {@link ConcurrentHashMap} 模拟链上存储，验证 ChainDidService 的
 * 链上注册/解析/更新/吊销逻辑。生产环境替换为真实链上合约实现。
 *
 * <p>使用 {@code @Component} 让 Spring 容器自动装配，使 {@link ChainDidService}
 * 的构造参数能找到唯一的 {@link ChainDidStore} 候选 Bean。
 *
 * @since 2.12.0
 */
@Component
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