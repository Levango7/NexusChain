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

package org.nexus.core.account;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 账户存储健康状态的轻量快照。
 *
 * <p>只记录账户读取路径的最近一次成功/失败，用于运维接口区分
 * “链正常但账户库降级”和“节点完全正常”。不参与共识判断，也不替代
 * 数据库自身的健康检查。</p>
 */
@Component
public class AccountStoreHealth {

    private final AtomicBoolean degraded = new AtomicBoolean(false);
    private final AtomicLong lastFailureAt = new AtomicLong(0);
    private final AtomicLong lastSuccessAt = new AtomicLong(0);
    private final AtomicReference<String> lastFailure = new AtomicReference<>("");

    public void recordSuccess() {
        lastSuccessAt.set(System.currentTimeMillis());
        degraded.set(false);
    }

    public void recordFailure(Throwable error) {
        lastFailureAt.set(System.currentTimeMillis());
        lastFailure.set(describe(error));
        degraded.set(true);
    }

    public boolean isDegraded() {
        return degraded.get();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("state", isDegraded() ? "DEGRADED" : "UP");
        data.put("lastFailureAt", lastFailureAt.get());
        data.put("lastSuccessAt", lastSuccessAt.get());
        data.put("lastFailure", lastFailure.get());
        return data;
    }

    private static String describe(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getClass().getSimpleName()
                + (root.getMessage() == null ? "" : ": " + root.getMessage());
        message = message.replace('\r', ' ').replace('\n', ' ');
        return message.length() > 300 ? message.substring(0, 300) : message;
    }
}
