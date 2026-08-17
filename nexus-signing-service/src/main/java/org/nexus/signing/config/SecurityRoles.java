package org.nexus.signing.config;

/**
 * signing-service 角色常量定义（P2-F1 完整安全架构）。
 *
 * <p>本类集中定义签名服务所有角色名，供 {@code @PreAuthorize} 注解、
 * {@link JwtTokenProvider#generateToken} 签发 token、以及 gateway 侧
 * {@code FeignJwtRequestInterceptor} 构造服务间 token 时统一引用，
 * 避免角色字符串散落在多个类中导致拼写不一致。</p>
 *
 * <h3>角色分级矩阵</h3>
 * <table>
 *   <caption>表：端点 → 角色对照表</caption>
 *   <tr><th>角色</th><th>权限范围</th><th>典型端点</th></tr>
 *   <tr><td>{@link #SIGNER}</td><td>签名操作（消耗私钥使用配额）</td>
 *       <td>{@code /api/v1/transfers/sign}、{@code /ClientToTransferAccount}</td></tr>
 *   <tr><td>{@link #ADMIN}</td><td>管理操作（密钥管理、MPC 会话管理、keystore 解密）</td>
 *       <td>{@code /fromPassword}、{@code /modifyPassword}、{@code /keystoreTo*}、
 *           {@code /prikeyToPubkey}</td></tr>
 *   <tr><td>{@link #OPERATOR}</td><td>运维操作（健康检查、状态查询、Nonce 池查询）</td>
 *       <td>{@code /getNoncePool}、{@code /actuator/health}</td></tr>
 *   <tr><td>{@link #READ}</td><td>只读操作（地址校验、pubkey↔hash 转换等无状态钱包工具）</td>
 *       <td>{@code /verifyAddress}、{@code /pubkeyHashToAddress}、
 *           {@code /addressToPubkeyHash}、{@code /pubkeyStrToPubkeyHashStr}</td></tr>
 * </table>
 *
 * <h3>角色继承关系（hasRole 语义）</h3>
 * <p>本服务采用「扁平角色」模型，不引入角色继承层级（避免
 * {@code ROLE_ADMIN} 自动拥有 {@code ROLE_SIGNER} 等隐式提权风险）。
 * 调用方需在 JWT 的 {@code roles} claim 中显式声明所需角色。
 * gateway 服务间 token 通常同时声明 {@link #SIGNER} + {@link #OPERATOR}
 * + {@link #READ}，{@link #ADMIN} 仅由专用离线签发流程授予。</p>
 *
 * <h3>与 P1-F1 的兼容性</h3>
 * <p>P1-F1 阶段使用 {@code SIGNING_SERVICE} 作为服务间统一角色名。
 * 为保持与 gateway 侧 {@code FeignJwtRequestInterceptor} 已签发 token
 * 的向后兼容，本类保留 {@link #SIGNING_SERVICE_LEGACY} 常量，
 * 并在 {@link SecurityConfig} 中将其映射为 {@link #SIGNER} 别名
 * （见 {@link JwtAuthenticationFilter} 的角色归一化逻辑）。</p>
 */
public final class SecurityRoles {

    private SecurityRoles() {
        // 常量类，禁止实例化
    }

    /**
     * 签名操作角色：可调用签名端点（消耗平台热钱包私钥使用配额）。
     * <p>对应端点：{@code /api/v1/transfers/sign}、{@code /ClientToTransferAccount}。</p>
     */
    public static final String SIGNER = "SIGNER";

    /**
     * 管理操作角色：可执行密钥管理、MPC 会话管理、keystore 解密类操作。
     * <p>对应端点：{@code /fromPassword}、{@code /modifyPassword}、
     * {@code /keystoreToAddress}、{@code /keystoreToPubkey}、
     * {@code /keystoreToPubkeyHash}、{@code /prikeyToPubkey}。</p>
     * <p>默认不签发：需通过专用离线流程签发短期 ADMIN token 后访问
     * （见 SecurityConfig javadoc）。</p>
     */
    public static final String ADMIN = "ADMIN";

    /**
     * 运维操作角色：可执行健康检查、状态查询、Nonce 池查询等运维只读端点。
     * <p>对应端点：{@code /getNoncePool}、{@code /actuator/health/**}。</p>
     */
    public static final String OPERATOR = "OPERATOR";

    /**
     * 只读操作角色：可执行地址校验、pubkey↔hash 转换等无状态钱包工具端点。
     * <p>对应端点：{@code /verifyAddress}、{@code /pubkeyHashToAddress}、
     * {@code /addressToPubkeyHash}、{@code /pubkeyStrToPubkeyHashStr}。</p>
     */
    public static final String READ = "READ";

    /**
     * P1-F1 阶段遗留的服务间统一角色名。
     * <p>保留以兼容 gateway 侧已签发的 token；新签发的 token 应使用
     * {@link #SIGNER} 等细粒度角色。{@link JwtAuthenticationFilter}
     * 解析时会将此角色视为 {@link #SIGNER} 别名。</p>
     */
    public static final String SIGNING_SERVICE_LEGACY = "SIGNING_SERVICE";
}