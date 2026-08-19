package org.nexus.compliance.identity;

/**
 * 链上 DID 存储接口。
 *
 * <p>抽象 DID 文档在链上的持久化能力：注册、解析、更新、吊销。
 * 生产环境由链上合约实现，测试环境由 {@link InMemoryChainDidStore} 模拟。
 *
 * @since 2.12.0
 */
public interface ChainDidStore {

    /** 注册 DID 文档到链上 */
    void register(String did, DidDocument document);

    /** 从链上解析 DID 文档 */
    DidDocument resolve(String did);

    /** 更新链上 DID 文档 */
    void update(String did, DidDocument document);

    /** 吊销 DID（链上标记） */
    void revoke(String did);

    /** DID 是否存在于链上 */
    boolean exists(String did);

    /** DID 是否已被吊销 */
    boolean isRevoked(String did);
}