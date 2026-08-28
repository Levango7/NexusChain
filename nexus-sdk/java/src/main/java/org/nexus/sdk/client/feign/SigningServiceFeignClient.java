package org.nexus.sdk.client.feign;

import org.nexus.sdk.client.feign.fallback.SigningServiceFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
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
     */
    @PostMapping(value = "/transfers/sign", consumes = "application/x-www-form-urlencoded")
    String signTransfer(@RequestParam("fromPubkey") String fromPubkey,
                        @RequestParam("toPubkeyHash") String toPubkeyHash,
                        @RequestParam("amount") BigDecimal amount);

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

}