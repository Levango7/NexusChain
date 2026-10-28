package org.nexus.sdk.client.feign;

import org.nexus.sdk.client.feign.fallback.SigningServiceFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 签名服务 Feign 客户端契约（gateway → nexus-signing-service）。
 *
 * <p>定义调用方对「签名服务」REST 端点的声明式访问契约。对应
 * nexus-signing-service 迁移后的 {@code TxController}（签名 + 广播）
 * 端点，承载「涉及私钥」的敏感操作。</p>
 *
 * <p>本接口为 Phase 1 Feign 骨架：
 * <ul>
 *   <li>{@code name} 占位为 {@code nexus-signing-service}，实际服务名由
 *       Nacos 注册决定，消费方模块通过 {@code @EnableFeignClients} 扫描装配</li>
 *   <li>{@code fallbackFactory} 指向 {@link SigningServiceFallbackFactory}（Phase 3 绑定），
 *       实现类由各消费方模块提供（gateway / wallet-service 各自定制降级语义）</li>
 *   <li>方法签名对应现有 {@code TxController} 端点</li>
 * </ul></p>
 *
 * <p>与 {@link org.nexus.sdk.client.SigningServiceClient}（业务边界纯接口）
 * 区分：本接口带 Spring Web 注解，供 Feign 声明式调用；前者供进程内
 * InProcess/Http 实现复用。Phase 2 切换传输模式后，gateway 将直接注入本接口。</p>
 *
 * <p>端点对齐修复（任务 #317）：移除 TxController 中不存在的端点声明：
 * <ul>
 *   <li>{@code transfer}（{@code POST /api/v1/transfers}，带 prikey）——
 *       TxController 中无此端点，legacy 端点为 {@code /ClientToTransferAccount}
 *       且 prikey 已被安全移除</li>
 *   <li>{@code canSignViaMpc}（{@code GET /api/v1/signing/capability}）——
 *       TxController 中无此端点</li>
 *   <li>{@code getNoncePool}（{@code GET /api/v1/getNoncePool}）——
 *       TxController 实际路径为 {@code /getNoncePool}（不带 {@code /api/v1} 前缀），
 *       FeignClient {@code path="/api/v1"} 导致拼接不匹配；gateway 无直接调用，移除</li>
 * </ul></p>
 */
@FeignClient(
        name = "nexus-signing-service",
        path = "/api/v1",
        contextId = "signingServiceFeignClient",
        fallbackFactory = SigningServiceFallbackFactory.class
)
public interface SigningServiceFeignClient {

    /**
     * 使用平台密钥库签名并广播一笔转账（核心端点）。
     *
     * <p>对应 {@code POST /api/v1/transfers/sign}。调用方不传私钥，
     * 由签名服务使用服务端 PlatformKeystore 完成签名。</p>
     *
     * <p>契约修正（质量审查 2026-09-10）：签名服务 {@code TxController} 实际
     * 返回 {@code {"statusCode":2000,"data":"<txhash>","message":...}} JSON
     * 对象（见 TxController.toResponseMap）。原声明返回 {@code String} 会让
     * Jackson 在反序列化时抛 MismatchedInputException（JSON 对象 → String），
     * 触发 fallback 返回 null → 退款/提现被标记 FAILED——真实联调前不会被
     * 单测发现（全部 mock 返回字符串）。现改为返回 {@code Map<String, Object>}
     * 匹配真实响应形状；txHash 由 {@code SigningResponses.txHash(Map)} 提取。</p>
     */
    @PostMapping(value = "/transfers/sign", consumes = "application/x-www-form-urlencoded")
    java.util.Map<String, Object> signTransfer(@RequestParam("fromPubkey") String fromPubkey,
                        @RequestParam("toPubkeyHash") String toPubkeyHash,
                        @RequestParam("amount") BigDecimal amount);

    /**
     * A2（ADR-036 §2.2）：携带业务批准凭据的签名广播（2026-10-28 立项实施）。
     *
     * <p>对应 {@code POST /api/v1/transfers/sign}（同一端点，多一个可选参数）。
     * signing 侧校验凭据（存在 / 未过期 / 未消费 / 载荷摘要匹配）通过后
     * <b>跳过自身大额审批</b>直接签名广播；任一校验不过时<b>回落到既有审批流</b>
     * （返回 2001）——除「凭据已消费」返回 5003（可能已广播，不得再走人审，
     * 否则同一笔提现有二次放款通道）。</p>
     *
     * <p>{@code businessApprovalId} 应传业务单号（wallet 提现 requestId），
     * 且必须已通过 {@link #registerBusinessApproval} 登记同一载荷。</p>
     */
    @PostMapping(value = "/transfers/sign", consumes = "application/x-www-form-urlencoded")
    java.util.Map<String, Object> signTransferWithBusinessApproval(
                        @RequestParam("fromPubkey") String fromPubkey,
                        @RequestParam("toPubkeyHash") String toPubkeyHash,
                        @RequestParam("amount") BigDecimal amount,
                        @RequestParam("businessApprovalId") String businessApprovalId);

    /**
     * A2：登记一笔业务批准凭据（wallet → signing，2026-10-28 立项实施）。
     *
     * <p>对应 {@code POST /api/v1/signing/business-approvals}。幂等：同
     * {@code businessApprovalId} 重复登记且 {@code payloadHash} 相同 ⇒ 幂等返回
     * （2000）；payloadHash 不同 ⇒ 5002 拒绝（防"同单号换载荷"）。登记本身
     * 不触发任何资金动作。</p>
     *
     * <p>{@code payloadHash} 由 {@link org.nexus.sdk.signing.BusinessApprovalPayloads#canonicalHash}
     * 计算（登记端会按 body 字段重算比对）；{@code expiresAt} 为 ISO-8601
     * instant 字符串（wallet 侧按提现单 TTL 派生）。</p>
     *
     * @return {@code {statusCode:2000, data:{businessApprovalId, state}}}；冲突 5002、
     *         参数非法 5004；降级返回 null
     */
    @PostMapping(value = "/signing/business-approvals", consumes = "application/x-www-form-urlencoded")
    java.util.Map<String, Object> registerBusinessApproval(
                        @RequestParam("businessApprovalId") String businessApprovalId,
                        @RequestParam("payloadHash") String payloadHash,
                        @RequestParam("amount") BigDecimal amount,
                        @RequestParam(value = "currency", required = false, defaultValue = "USDT") String currency,
                        @RequestParam("fromPubkey") String fromPubkey,
                        @RequestParam("toPubkeyHash") String toPubkeyHash,
                        @RequestParam("expiresAt") String expiresAt,
                        @RequestParam(value = "approvers", required = false) String approversCsv);


    /**
     * 无副作用轻量探针（审计修复，任务 #317 的重新落地）。
     *
     * <p>对应 {@code GET /api/v1/transfers/capability}。供 gateway 健康检查
     * 替代 signTransfer 生产端点探测——原探针每 30 秒触发一次完整签名路径
     * （读平台密钥库 + 写签名审计日志）。返回 {@code {statusCode: 2000, data:
     * {platformPubkeyConfigured: bool}}}</p>
     */
    @GetMapping("/transfers/capability")
    Map<String, Object> getCapability();

    /**
     * 查询审批请求状态（执行腿轮询用）。
     *
     * <p>对应 {@code GET /api/v1/signing/approvals/{requestId}}。调用方在
     * {@code signTransfer} 收到 {@code statusCode=2001}（PENDING）后保存 approvalId，
     * 之后周期查询本端点；{@code status=APPROVED} 时再调
     * {@link #executeApproved(String)}。只读、不改变状态。</p>
     *
     * <p>返回信封 {@code {statusCode:2000, data:{requestId, status, expired, amount, ...}}}；
     * 不存在时 HTTP 404（Feign 抛异常 → 降级返回 null）。调用方从
     * {@code data.status} 取 {@code PENDING/APPROVED/REJECTED/EXPIRED/EXECUTING/EXECUTED}。</p>
     */
    @GetMapping("/signing/approvals/{requestId}")
    Map<String, Object> getApprovalStatus(@PathVariable("requestId") String requestId);

    /**
     * 执行已批准的转账（审批通过后的签名 + 广播）。
     *
     * <p>对应 {@code POST /api/v1/transfers/sign/approved}。签名服务内部用
     * APPROVED→EXECUTING 的 CAS 防重复执行；并发/重复调用中只有一个成功，
     * 其余返回 {@code statusCode=5001}。</p>
     *
     * @return 成功时形状与 {@code signTransfer} 相同（{@code {statusCode:2000,data:<txhash>}}）；
     *         未批准返回 5000、并发被抢返回 5001；降级返回 null
     */
    @PostMapping(value = "/transfers/sign/approved", consumes = "application/x-www-form-urlencoded")
    Map<String, Object> executeApproved(@RequestParam("approvalId") String approvalId);

}