package org.nexus.gateway.client.feign;

import feign.Response;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.Map;

/**
 * 签名审批决策的下游契约（gateway → nexus-signing-service）。
 *
 * <p>对应签名服务 {@code POST /api/v1/signing/approvals/{requestId}/decision}
 * （#50 新增的多签决策端点）。</p>
 *
 * <h3>为什么返回 {@link Response} 而不是解包后的 Map</h3>
 * <p>决策端点用 HTTP 状态区分语义：200 已记账、403 审批人无权（正文带
 * {@code denied_reason}）、404 请求不存在、409 已不在待审状态。若声明为
 * {@code Map}，Feign 的 {@code ErrorDecoder} 会把非 2xx 全部抛成
 * {@code FeignException}，网关就得再从异常里把状态和正文抠出来。直接返回
 * {@code Response} 让 Feign 跳过错误解码，网关原样透传状态码与正文，
 * 不重写下游语义。</p>
 *
 * <h3>为什么 Authorization 是显式入参</h3>
 * <p>本调用必须带<b>调用者本人</b>的 JWT：signing-service 从 token 的 {@code sub}
 * 取审批人身份（不读请求体），白名单与审计都按它判定。网关的
 * {@code FeignJwtRequestInterceptor} 只在模板里<b>没有</b> Authorization 头时才注入
 * 服务身份 token（subject={@code nexus-gateway}），因此显式传入的头会被保留，
 * 服务身份不会顶替运维身份。</p>
 */
@FeignClient(
        name = "nexus-signing-service",
        path = "/api/v1",
        contextId = "signingApprovalFeignClient"
)
public interface SigningApprovalFeignClient {

    /**
     * 转发一次审批决策。
     *
     * @param requestId     审批请求 ID
     * @param authorization 调用者的 {@code Authorization: Bearer <jwt>} 原值
     * @param body          {@code {"decision": "APPROVE"|"REJECT", "comment": "..."}}
     * @return 下游原始响应（状态码 + JSON 信封）
     */
    @PostMapping(value = "/signing/approvals/{requestId}/decision", consumes = "application/json")
    Response decide(@PathVariable("requestId") String requestId,
                    @RequestHeader("Authorization") String authorization,
                    @RequestBody Map<String, Object> body);
}
