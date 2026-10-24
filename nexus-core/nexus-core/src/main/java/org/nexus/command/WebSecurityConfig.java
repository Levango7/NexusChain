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

    @Autowired
    RpcInterceptor rpcInterceptor;

    @Autowired
    LocalRpcInterceptor localRpcInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rpcInterceptor).addPathPatterns("/NexusChainCore/*");
        // 注意：P2P 对端直连的 /consensus/* 端点必须全部列入白名单（LocalRpcInterceptor
        // 挂 /**）。PLAN-032 曾漏加 /consensus/validator-set ⇒ 被拦截为「200 空响应」
        // （rest 端拉到空数组，静默失效）——新增 P2P 端点时同步加到这里。
        registry.addInterceptor(localRpcInterceptor).addPathPatterns("/**").excludePathPatterns("/consensus/blocks","/consensus/status","/consensus/status","/consensus/blocks","/consensus/storage-status","/consensus/finality-outbox","/consensus/block-outbox","/consensus/validator-set","/consensus/catch-up","/NexusChainCore/*");
    }
}