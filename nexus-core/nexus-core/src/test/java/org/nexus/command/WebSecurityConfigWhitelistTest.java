package org.nexus.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.AntPathMatcher;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WebSecurityConfig#LOCAL_ONLY_PUBLIC_PATHS} 白名单守卫（2026-10-25 主干红事后）。
 *
 * <p>背景：{@code nexus.localonly} 默认 true，而 {@link LocalRpcInterceptor} 挂在
 * {@code /**}。#233 把拒绝从"静默 200 空响应"改成"显式 401"之后，漏配的路径
 * 从"看起来正常"变成"直接打死探针"——主干 CI 的 DAST 因此红：
 * core 容器 unhealthy（日志 {@code Local-only RPC rejected: missing token from 127.0.0.1}），
 * 连带 {@code depends_on: service_healthy} 的 gateway/signing 起不来。</p>
 *
 * <p>所以本用例钉的是<b>匹配语义</b>（不是只看数组里有没有这个字符串）：
 * 拼错的 {@code /heights}、{@code /height/} 之类的写法都必须失败。</p>
 */
class WebSecurityConfigWhitelistTest {

    private final AntPathMatcher matcher = new AntPathMatcher();

    private boolean isPublic(String path) {
        return Arrays.stream(WebSecurityConfig.LOCAL_ONLY_PUBLIC_PATHS)
                .anyMatch(pattern -> matcher.match(pattern, path));
    }

    @Test
    @DisplayName("容器/k8s 探针路径 /height 必须免鉴权（否则 core 被判 unhealthy）")
    void probePathIsPublic() {
        assertTrue(isPublic("/height"),
                "/height 是 docker-compose healthcheck 与 helm liveness/readiness 的探针路径");
    }

    @Test
    @DisplayName("P2P 对端直连的 /consensus/* 全部免鉴权（PLAN-032 踩坑点）")
    void consensusP2pEndpointsArePublic() {
        for (String path : List.of("/consensus/blocks", "/consensus/status",
                "/consensus/storage-status", "/consensus/finality-outbox",
                "/consensus/block-outbox", "/consensus/validator-set", "/consensus/catch-up")) {
            assertTrue(isPublic(path), path + " 必须留在白名单，否则对端拿到静默空响应");
        }
    }

    @Test
    @DisplayName("旧 RPC 前缀交由 RpcInterceptor 管，仍在免 local-only 名单内")
    void legacyRpcPrefixStaysExcluded() {
        assertTrue(isPublic("/NexusChainCore/getBlock"));
    }

    @Test
    @DisplayName("未列出的路径仍受 local-only 保护（漏配不等于放行）")
    void unlistedPathsStayProtected() {
        // 真实存在的写/查命令端点：不在白名单 ⇒ localonly=true 时应当被拒
        assertFalse(isPublic("/getTransactionHeight"), "命令端点不得被顺手放行");
        assertFalse(isPublic("/transactions"), "交易查询端点不得被顺手放行");
        assertFalse(isPublic("/"), "根路径不得被顺手放行");
    }

    @Test
    @DisplayName("拼错的探针路径不算命中（钉住匹配语义而非字符串包含）")
    void nearMissPathsAreNotMatched() {
        assertFalse(isPublic("/heights"));
        assertFalse(isPublic("/proxy/height"));
        assertFalse(isPublic("/consensus"));
    }

    @Test
    @DisplayName("白名单无重复项、无空项（旧实现里 /consensus/blocks 与 /status 各写了两遍）")
    void whitelistHasNoDuplicatesOrBlanks() {
        List<String> patterns = Arrays.asList(WebSecurityConfig.LOCAL_ONLY_PUBLIC_PATHS);
        assertEquals(patterns.stream().distinct().count(), patterns.size(),
                "白名单存在重复项: " + patterns);
        assertTrue(patterns.stream().allMatch(p -> p != null && !p.isBlank()),
                "白名单存在空项: " + patterns);
    }
}
