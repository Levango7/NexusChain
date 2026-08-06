package org.nexus.sdk.client.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * 跨链桥服务 Feign 客户端契约（gateway → nexus-bridge）。
 *
 * <p>定义调用方对「跨链桥服务」REST 端点的声明式访问契约，覆盖
 * lock/mint/burn/unlock 跨链操作与交易查询。对应 nexus-bridge
 * 的 {@code BridgeController}（{@code /api/v1/bridge/**}）。</p>
 *
 * <p>本接口为 Phase 1 Feign 骨架：
 * <ul>
 *   <li>{@code name} 占位为 {@code nexus-bridge}，实际服务名由
 *       Nacos 注册决定，消费方模块通过 {@code @EnableFeignClients} 扫描装配</li>
 *   <li>{@code fallback} 未指定，Phase 2 #61 任务补全 Sentinel 降级类</li>
 *   <li>请求/响应体暂用 {@code Map<String, Object>} 占位，Phase 2 #60
 *       任务将替换为 nexus-sdk 内定义的跨链桥 DTO（LockRequest/MintRequest 等）</li>
 * </ul></p>
 */
@FeignClient(
        name = "nexus-bridge",
        path = "/api/v1/bridge",
        contextId = "bridgeServiceFeignClient"
)
public interface BridgeServiceFeignClient {

    /**
     * 锁定源链资产（跨链转入）。
     *
     * <p>对应 {@code POST /api/v1/bridge/lock}。请求体对应 bridge 的 LockRequest。</p>
     */
    @PostMapping("/lock")
    Map<String, Object> lock(Map<String, Object> request);

    /**
     * 在目标链铸造映射资产。
     *
     * <p>对应 {@code POST /api/v1/bridge/mint}。请求体对应 bridge 的 MintRequest。</p>
     */
    @PostMapping("/mint")
    Map<String, Object> mint(Map<String, Object> request);

    /**
     * 在目标链销毁映射资产（跨链转出）。
     *
     * <p>对应 {@code POST /api/v1/bridge/burn}。请求体对应 bridge 的 BurnRequest。</p>
     */
    @PostMapping("/burn")
    Map<String, Object> burn(Map<String, Object> request);

    /**
     * 在源链解锁资产。
     *
     * <p>对应 {@code POST /api/v1/bridge/unlock}。请求体对应 bridge 的 UnlockRequest。</p>
     */
    @PostMapping("/unlock")
    Map<String, Object> unlock(Map<String, Object> request);

    /**
     * 按桥交易 ID 查询跨链交易详情。
     *
     * <p>对应 {@code GET /api/v1/bridge/tx/{txId}}。</p>
     */
    @GetMapping("/tx/{txId}")
    Map<String, Object> getTransaction(@PathVariable("txId") String txId);

    /**
     * 按源链交易哈希查询跨链交易。
     *
     * <p>对应 {@code GET /api/v1/bridge/tx?sourceTxHash=...}。</p>
     */
    @GetMapping("/tx")
    Map<String, Object> getBySourceHash(@RequestParam("sourceTxHash") String sourceTxHash);

    /**
     * 查询桥状态。
     *
     * <p>对应 {@code GET /api/v1/bridge/status}。</p>
     */
    @GetMapping("/status")
    Map<String, Object> status();
}