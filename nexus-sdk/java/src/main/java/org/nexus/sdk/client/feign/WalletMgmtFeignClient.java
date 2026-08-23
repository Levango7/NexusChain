package org.nexus.sdk.client.feign;

import org.nexus.sdk.client.feign.fallback.WalletMgmtFallbackFactory;
import org.nexus.sdk.wallet.WithdrawalRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;

/**
 * 钱包管理服务 Feign 客户端契约（gateway → nexus-wallet-service）。
 *
 * <p>定义调用方对「钱包管理服务」REST 端点的声明式访问契约，覆盖
 * 提现审批（withdrawal approval）、白名单（whitelist）两类「不涉及私钥」的
 * 钱包管理操作。</p>
 *
 * <p>本接口为 Phase 1 Feign 骨架：
 * <ul>
 *   <li>{@code name} 占位为 {@code nexus-wallet-service}，实际服务名由
 *       Nacos 注册决定，消费方模块通过 {@code @EnableFeignClients} 扫描装配</li>
 *   <li>{@code fallbackFactory} 指向 {@link WalletMgmtFallbackFactory}（Phase 3 绑定），
 *       实现类由各消费方模块提供（gateway 定制降级语义）</li>
 *   <li>方法签名对应 wallet-service 迁移后的 RESTful 端点（/api/v1/wallet/**）</li>
 * </ul></p>
 *
 * <p>与 {@link org.nexus.sdk.client.WalletMgmtClient}（业务边界纯接口）
 * 区分：本接口带 Spring Web 注解，供 Feign 声明式调用；前者供进程内
 * InProcess/Http 实现复用。</p>
 *
 * <p>端点对齐修复（任务 #317）：移除/修正 WalletController 中不存在或路径不匹配的端点：
 * <ul>
 *   <li>移除 {@code addressToPubkeyHash} / {@code verifyAddress} ——
 *       WalletController 中无此端点；gateway 调用方改用
 *       {@link org.nexus.sdk.wallet.WalletUtils} 静态方法本地计算</li>
 *   <li>修正 {@code approveWithdrawal} / {@code rejectWithdrawal} / {@code executeWithdrawal} ——
 *       路径从 {@code /withdrawal/{requestId}/approve} 改为 {@code /withdrawal/approve}，
 *       参数从 {@code @PathVariable} 改为 {@code @RequestParam("approvalId")}，
 *       移除 {@code approverId} 参数（Controller 从认证上下文获取）</li>
 *   <li>移除 {@code getWithdrawal} / {@code compensateWithdrawal} ——
 *       WalletController 中无此端点</li>
 *   <li>移除 {@code getCustodyTier} / {@code depositToCold} / {@code withdrawFromCold} ——
 *       WalletController 中无此端点</li>
 * </ul></p>
 */
@FeignClient(
        name = "nexus-wallet-service",
        path = "/api/v1/wallet",
        contextId = "walletMgmtFeignClient",
        fallbackFactory = WalletMgmtFallbackFactory.class
)
public interface WalletMgmtFeignClient {

    // === 提现审批 ===

    /**
     * 发起一笔提现请求。
     *
     * <p>对应 {@code POST /api/v1/wallet/withdrawal/request}。</p>
     */
    @PostMapping("/withdrawal/request")
    WithdrawalRequest requestWithdrawal(@RequestParam("to") String to,
                                        @RequestParam("amount") BigDecimal amount,
                                        @RequestParam("currency") String currency);

    /**
     * 对提现请求追加一次审批。
     *
     * <p>对应 {@code POST /api/v1/wallet/withdrawal/approve}。
     * 审批人 ID（{@code approverId}）由 wallet-service 从认证上下文
     * （JWT subject）获取，调用方无需传递。</p>
     *
     * @param approvalId 提现申请 ID
     * @return 更新后的提现申请
     */
    @PostMapping("/withdrawal/approve")
    WithdrawalRequest approveWithdrawal(@RequestParam("approvalId") String approvalId);

    /**
     * 拒绝提现请求。
     *
     * <p>对应 {@code POST /api/v1/wallet/withdrawal/reject}。
     * 审批人 ID 由 wallet-service 从认证上下文获取。</p>
     *
     * @param approvalId 提现申请 ID
     * @param reason     拒绝原因（可选）
     * @return 更新后的提现申请
     */
    @PostMapping("/withdrawal/reject")
    WithdrawalRequest rejectWithdrawal(@RequestParam("approvalId") String approvalId,
                                       @RequestParam(value = "reason", required = false) String reason);

    /**
     * 执行已批准的提现（触发签名服务签名 + 广播）。
     *
     * <p>对应 {@code POST /api/v1/wallet/withdrawal/execute}。</p>
     *
     * @param approvalId 提现申请 ID
     * @return 更新后的提现申请（EXECUTED 或 FAILED）
     */
    @PostMapping("/withdrawal/execute")
    WithdrawalRequest executeWithdrawal(@RequestParam("approvalId") String approvalId);

    // === 白名单 ===

    /**
     * 查询指定地址是否在提现白名单中。
     *
     * <p>对应 {@code GET /api/v1/wallet/whitelist/check}。</p>
     */
    @GetMapping("/whitelist/check")
    boolean isAddressWhitelisted(@RequestParam("address") String address);

    /**
     * 新增白名单地址。
     *
     * <p>对应 {@code POST /api/v1/wallet/whitelist/add}。</p>
     */
    @PostMapping("/whitelist/add")
    Object addWhitelist(@RequestParam("address") String address,
                        @RequestParam("label") String label,
                        @RequestParam("merchantId") String merchantId);
}
