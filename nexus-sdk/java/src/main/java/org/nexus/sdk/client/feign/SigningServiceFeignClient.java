package org.nexus.sdk.client.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;

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
 *   <li>{@code fallback} 未指定，Phase 2 #61 任务补全 Sentinel 降级类</li>
 *   <li>方法签名对应现有 {@code TxController} 端点</li>
 *   <li>地址类工具（{@code addressToPubkeyHash}/{@code verifyAddress}）按方案 §4.4.1
 *       归属 {@link WalletMgmtFeignClient}（钱包管理服务，不涉及私钥）</li>
 * </ul></p>
 *
 * <p>与 {@link org.nexus.sdk.client.SigningServiceClient}（业务边界纯接口）
 * 区分：本接口带 Spring Web 注解，供 Feign 声明式调用；前者供进程内
 * InProcess/Http 实现复用。Phase 2 切换传输模式后，gateway 将直接注入本接口。</p>
 */
@FeignClient(
        name = "nexus-signing-service",
        path = "/api/v1",
        contextId = "signingServiceFeignClient"
)
public interface SigningServiceFeignClient {

    /**
     * 使用平台密钥库签名并广播一笔转账（核心端点）。
     *
     * <p>对应 {@code POST /api/v1/transfers/sign}。调用方不传私钥，
     * 由签名服务使用服务端 PlatformKeystore 完成签名。</p>
     */
    @PostMapping(value = "/transfers/sign", consumes = "application/x-www-form-urlencoded")
    String signTransfer(@RequestParam("fromPubkey") String fromPubkey,
                        @RequestParam("toPubkeyHash") String toPubkeyHash,
                        @RequestParam("amount") BigDecimal amount);

    /**
     * Legacy 转账端点（调用方提供私钥），迁移期保留。
     *
     * <p>对应 {@code POST /api/v1/transfers}。新代码应使用
     * {@link #signTransfer} 避免传输私钥。</p>
     */
    @PostMapping(value = "/transfers", consumes = "application/x-www-form-urlencoded")
    String transfer(@RequestParam("fromPubkey") String fromPubkey,
                    @RequestParam("toPubkeyHash") String toPubkeyHash,
                    @RequestParam("amount") BigDecimal amount,
                    @RequestParam("prikey") String privateKey);

    /**
     * 判断指定金额是否可通过 MPC 流程签名。
     *
     * <p>对应 {@code GET /api/v1/signing/capability}。</p>
     */
    @GetMapping("/signing/capability")
    boolean canSignViaMpc(@RequestParam("amount") BigDecimal amount);

    /**
     * 查询指定地址的 Nonce 池快照。
     *
     * <p>对应 {@code GET /getNoncePool}（legacy 路径，迁移期保留）。</p>
     */
    @GetMapping("/getNoncePool")
    Object getNoncePool(@RequestParam("address") String address);

}