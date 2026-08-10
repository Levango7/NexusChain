package org.nexus.sdk.client.feign;

import org.nexus.sdk.client.feign.fallback.WalletMgmtFallbackFactory;
import org.nexus.sdk.wallet.WithdrawalRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;

/**
 * 钱包管理服务 Feign 客户端契约（gateway → nexus-wallet-service）。
 *
 * <p>定义调用方对「钱包管理服务」REST 端点的声明式访问契约，覆盖
 * 地址工具（addressToPubkeyHash/verifyAddress）、提现审批（withdrawal approval）、
 * 托管（custody）、白名单（whitelist）四类「不涉及私钥」的钱包管理操作。</p>
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
 */
@FeignClient(
        name = "nexus-wallet-service",
        path = "/api/v1/wallet",
        contextId = "walletMgmtFeignClient",
        fallbackFactory = WalletMgmtFallbackFactory.class
)
public interface WalletMgmtFeignClient {

    // === 地址工具（无状态，不涉及私钥） ===

    /**
     * 将 NEX 地址转换为公钥哈希。
     *
     * <p>对应 {@code GET /api/v1/wallet/addressToPubkeyHash}。原 exchange-wallet
     * 的无状态钱包工具，按方案 §4.4.1 迁入钱包管理服务（与地址校验同属
     * 「不涉及私钥」的地址类操作）。</p>
     *
     * @param address NEX 地址
     * @return 公钥哈希 hex 字符串，失败返回 {@code null}
     */
    @GetMapping("/addressToPubkeyHash")
    String addressToPubkeyHash(@RequestParam("address") String address);

    /**
     * 校验 NEX 地址合法性。
     *
     * <p>对应 {@code GET /api/v1/wallet/verifyAddress}。</p>
     *
     * @param address NEX 地址
     * @return {@code true} 表示合法
     */
    @GetMapping("/verifyAddress")
    boolean verifyAddress(@RequestParam("address") String address);

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
     * <p>对应 {@code POST /api/v1/wallet/withdrawal/{requestId}/approve}。</p>
     */
    @PostMapping("/withdrawal/{requestId}/approve")
    WithdrawalRequest approveWithdrawal(@PathVariable("requestId") String requestId,
                                        @RequestParam("approverId") String approverId);

    /**
     * 拒绝提现请求。
     *
     * <p>对应 {@code POST /api/v1/wallet/withdrawal/{requestId}/reject}。</p>
     */
    @PostMapping("/withdrawal/{requestId}/reject")
    WithdrawalRequest rejectWithdrawal(@PathVariable("requestId") String requestId,
                                       @RequestParam("approverId") String approverId,
                                       @RequestParam("reason") String reason);

    /**
     * 执行已批准的提现（触发签名服务签名 + 广播）。
     *
     * <p>对应 {@code POST /api/v1/wallet/withdrawal/{requestId}/execute}。</p>
     */
    @PostMapping("/withdrawal/{requestId}/execute")
    WithdrawalRequest executeWithdrawal(@PathVariable("requestId") String requestId);

    /**
     * 查询提现请求详情。
     *
     * <p>对应 {@code GET /api/v1/wallet/withdrawal/{requestId}}。</p>
     */
    @GetMapping("/withdrawal/{requestId}")
    WithdrawalRequest getWithdrawal(@PathVariable("requestId") String requestId);

    // === 托管 ===

    /**
     * 查询指定钱包的托管层级。
     *
     * <p>对应 {@code GET /api/v1/wallet/custody/tier}。返回 HOT/WARM/COLD。</p>
     */
    @GetMapping("/custody/tier")
    String getCustodyTier(@RequestParam("walletId") String walletId);

    /**
     * 热钱包向冷钱包归集。
     *
     * <p>对应 {@code POST /api/v1/wallet/custody/deposit-to-cold}。</p>
     */
    @PostMapping("/custody/deposit-to-cold")
    String depositToCold(@RequestParam("address") String address,
                         @RequestParam("amount") BigDecimal amount);

    /**
     * 冷钱包向热钱包提取（需多签审批）。
     *
     * <p>对应 {@code POST /api/v1/wallet/custody/withdraw-from-cold}。</p>
     */
    @PostMapping("/custody/withdraw-from-cold")
    String withdrawFromCold(@RequestParam("address") String address,
                            @RequestParam("amount") BigDecimal amount,
                            @RequestParam("approvalId") String approvalId);

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