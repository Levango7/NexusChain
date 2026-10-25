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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.nexus.util.JWTUtil;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class LocalRpcInterceptor implements HandlerInterceptor {

    private static Logger logger = LoggerFactory.getLogger(LocalRpcInterceptor.class);

    @Value("${nexus.localonly}")
    private boolean localonly;

    /**
     * 拒绝时显式回 401 + JSON body（2026-10-25 加固）。
     *
     * <p>旧实现 {@code return false} 且不写响应 ⇒ 客户端拿到 **HTTP 200 空 body**：
     * 调用方无法区分"被拦截/被限流/正常返回空"，排查时极易误判。（PLAN-032 的
     * 白名单踩坑正是被这个静默失败放大的。）</p>
     */
    private void reject(HttpServletRequest request, HttpServletResponse response, String reason)
            throws java.io.IOException {
        logger.warn("Local-only RPC rejected: {} from {}:{}", reason,
                request.getRemoteHost(), request.getRemotePort());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        String body = "{\"code\":401,\"message\":\"local-only rpc rejected: " + reason + "\"}";
        response.getWriter().write(body);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws java.io.IOException {
        if (localonly) {
            String token = request.getHeader("token");
            // 判断：非 null 且非空串（旧实现用引用比较 token != ""，动态空串会被当成合法值）
            if (token != null && !token.isEmpty()) {
                try {
                    if (JWTUtil.parseJWT(token)) {
                        return true;
                    }
                } catch (RuntimeException e) {
                    reject(request, response, "malformed token: " + e.getClass().getSimpleName());
                    return false;
                }
                // 验签不通过：同样显式 401（旧实现这里静默 return false）
                reject(request, response, "invalid token");
                return false;
            }
            reject(request, response, "missing token");
            return false;
        }
        return true;
    }
}
