/*
 * Copyright (c) [2018]
 * This file is part of the java-nexuscore
 *
 * The java-nexuscore is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The java-nexuscore is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with the java-nexuscore. If not, see <http://www.gnu.org/licenses/>.
 */

package org.nexus.command;

import org.nexus.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;

@Component
public class RpcInterceptor implements HandlerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(RpcInterceptor.class);

    /** 令牌请求头名（保持与既有调用方一致）。 */
    private static final String TOKEN_HEADER = "token";

    /**
     * 孵化（incubate）RPC 共享令牌，保护 {@code /NexusChainCore/*}（见 WebSecurityConfig）。
     *
     * <p><b>S-1 修复（2026-09-17 交付前审计）</b>：原实现把令牌以<b>字符串字面量</b>与
     * 请求头直接比较（该字面量随源码入库，任何能读到源码/反编译 jar 的人都可直接调用，
     * 且无法轮换）。现改为配置注入（{@code nexus.security.rpc-incubate-token} /
     * 环境变量 {@code NEXUS_RPC_INCUBATE_TOKEN}）+ 常量时间比较 + <b>fail-closed</b>：
     * 未配置时拒绝一切请求，需显式注入令牌才放行。</p>
     *
     * <p>注意：原硬编码值仍存在于 git 历史中，已视为泄露，<b>不得</b>继续使用；
     * 请注入一个新产生的随机令牌。</p>
     */
    private final String incubateToken;

    public RpcInterceptor(@Value("${nexus.security.rpc-incubate-token:}") String incubateToken) {
        this.incubateToken = incubateToken == null ? "" : incubateToken;
        if (this.incubateToken.isEmpty()) {
            logger.warn("nexus.security.rpc-incubate-token is not configured — /NexusChainCore/* "
                    + "will reject ALL requests (fail-closed). Set env NEXUS_RPC_INCUBATE_TOKEN "
                    + "to a freshly generated random token to enable incubate RPC.");
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // fail-closed：未配置令牌 = 拒绝（原实现为硬编码静态令牌，恒可被外部复用）
        if (incubateToken.isEmpty()) {
            logger.warn("Incubate RPC rejected (token not configured) from "
                    + request.getRemoteHost() + ":" + request.getRemotePort());
            return false;
        }
        String token = request.getHeader(TOKEN_HEADER);
        if (token == null || token.isEmpty()) {
            logger.warn("Illegal incubate connection request from " + request.getRemoteHost() + ":" + request.getRemotePort());
            return false;
        }
        // 常量时间比较（避免时序侧信道；String.equals 会提前返回）
        boolean matched = Arrays.constantTimeAreEqual(
                incubateToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
        if (!matched) {
            logger.warn("Illegal incubate connection request from " + request.getRemoteHost() + ":" + request.getRemotePort());
            return false;
        }
        return true;
    }
}