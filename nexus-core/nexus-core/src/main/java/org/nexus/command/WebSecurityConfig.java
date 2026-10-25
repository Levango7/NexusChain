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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebSecurityConfig implements WebMvcConfigurer {

    /**
     * {@link LocalRpcInterceptor} 的公开路径白名单（容器探针 + P2P 对端直连）。
     *
     * <p><b>{@code /height} 为什么必须在列</b>：{@code nexus.localonly} 默认
     * {@code true}（application.properties 里 {@code ${LOCALONLY:true}}），而
     * docker-compose 的 healthcheck 与 helm nexus-core 的 liveness/readiness 探针
     * 打的都是 {@code GET /height}。2026-10-25 的教训：#233 把拒绝路径从
     * "静默 200 空响应" 改成 "显式 401" 之后，漏配白名单不再看起来正常，而是直接把
     * core 判成 unhealthy —— 主干 CI 实测红：
     * {@code dependency failed to start: container nexuschain-nexus-core-1 is unhealthy}，
     * 容器日志 {@code Local-only RPC rejected: missing token from 127.0.0.1}。
     * 探针失败还会连带 {@code depends_on: service_healthy} 的 gateway/signing 全链起不来。</p>
     *
     * <p>PLAN-032 的另一面：漏加 {@code /consensus/validator-set} 当时表现为
     * "200 空数组"的静默失效。<b>两条都指向同一条纪律：新增探针或 P2P 端点必须同步这里。</b></p>
     */
    static final String[] LOCAL_ONLY_PUBLIC_PATHS = {
            "/height",
            "/consensus/blocks",
            "/consensus/status",
            "/consensus/storage-status",
            "/consensus/finality-outbox",
            "/consensus/block-outbox",
            "/consensus/block-writer",
            "/consensus/validator-set",
            "/consensus/catch-up",
            "/NexusChainCore/*"
    };

    @Autowired
    RpcInterceptor rpcInterceptor;

    @Autowired
    LocalRpcInterceptor localRpcInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rpcInterceptor).addPathPatterns("/NexusChainCore/*");
        registry.addInterceptor(localRpcInterceptor).addPathPatterns("/**")
                .excludePathPatterns(LOCAL_ONLY_PUBLIC_PATHS);
    }
}