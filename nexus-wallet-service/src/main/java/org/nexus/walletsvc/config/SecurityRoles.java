package org.nexus.walletsvc.config;

/**
 * wallet-service 角色常量定义（P0-3 安全加固）。
 *
 * <p>本类集中定义钱包管理服务所有角色名，供 {@code @PreAuthorize} 注解、
 * {@link JwtTokenProvider#generateToken} 签发 token、以及 gateway 侧
 * {@code FeignJwtRequestInterceptor} 构造服务间 token 时统一引用，
 * 避免角色字符串散落在多个类中导致拼写不一致。</p>
 *
 * <h3>角色分级矩阵</h3>
 * <table>
 *   <caption>表：端点 → 角色对照表</caption>
 *   <tr><th>角色</th><th>权限范围</th><th>典型端点</th></tr>
 *   <tr><td>{@link #OPERATOR}</td><td>运维操作（白名单查询、提现申请、提现执行、托管余额查询）</td>
 *       <td>{@code /whitelist/check}、{@code /withdrawal/request}、
 *           {@code /withdrawal/execute}、{@code /custody/balance}</td></tr>
 *   <tr><td>{@link #ADMIN}</td><td>管理操作（白名单管理、托管再平衡）</td>
 *       <td>{@code /whitelist/add}、{@code /whitelist/remove}、
 *           {@code /custody/rebalance}</td></tr>
 *   <tr><td>{@link #APPROVER}</td><td>审批操作（提现审批、拒绝）</td>
 *       <td>{@code /withdrawal/approve}、{@code /withdrawal/reject}</td></tr>
 * </table>
 *
 * <h3>角色继承关系（hasRole 语义）</h3>
 * <p>本服务采用「扁平角色」模型，不引入角色继承层级（避免
 * {@code ROLE_ADMIN} 自动拥有 {@code ROLE_OPERATOR} 等隐式提权风险）。
 * 调用方需在 JWT 的 {@code roles} claim 中显式声明所需角色。</p>
 *
 * <h3>与 signing-service 的关系</h3>
 * <p>本类与 {@code org.nexus.signing.config.SecurityRoles} 独立定义，
 * 两个服务部署单元各自维护角色常量。OPERATOR 角色在两个服务中语义一致
 * （运维只读/操作），ADMIN 角色亦然。APPROVER 为 wallet-service 独有
 * （提现审批流程仅在 wallet-service 承载）。</p>
 */
public final class SecurityRoles {

    private SecurityRoles() {
        // 常量类，禁止实例化
    }

    /**
     * 运维操作角色：可执行白名单查询、提现申请、提现执行、托管余额查询等运维端点。
     * <p>对应端点：{@code /whitelist/check}、{@code /withdrawal/request}、
     * {@code /withdrawal/execute}、{@code /custody/balance}。</p>
     */
    public static final String OPERATOR = "OPERATOR";

    /**
     * 管理操作角色：可执行白名单管理（增删）、托管再平衡等管理端点。
     * <p>对应端点：{@code /whitelist/add}、{@code /whitelist/remove}、
     * {@code /custody/rebalance}。</p>
     * <p>默认不签发给 gateway：需通过专用离线流程签发短期 ADMIN token 后访问。</p>
     */
    public static final String ADMIN = "ADMIN";

    /**
     * 审批操作角色：可执行提现审批、拒绝等审批端点。
     * <p>对应端点：{@code /withdrawal/approve}、{@code /withdrawal/reject}。</p>
     * <p>APPROVER 角色的 JWT subject（即审批人 ID）通过
     * {@code SecurityContextHolder.getContext().getAuthentication().getName()}
     * 获取，作为 approverId 传入审批服务，避免审批人自报 ID 的安全风险。</p>
     */
    public static final String APPROVER = "APPROVER";
}