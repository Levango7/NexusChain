package org.nexus.compliance.identity;

/**
 * 去中心化身份（DID）服务接口。
 * <p>
 * 负责 DID 的创建、解析与可验证凭证的校验。
 * </p>
 */
public interface DidService {

    /**
     * 创建 DID 文档。
     *
     * @return DID 文档
     */
    DidDocument createDid();

    /**
     * 解析 DID。
     *
     * @param did DID 标识
     * @return DID 文档
     */
    DidDocument resolveDid(String did);

    /**
     * 校验可验证凭证。
     *
     * @param credential 可验证凭证
     * @return true 表示校验通过
     */
    boolean verifyCredential(VerifiableCredential credential);
}