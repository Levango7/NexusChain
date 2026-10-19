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

package org.nexus.core.event;

import org.nexus.core.Block;
import org.springframework.context.ApplicationEvent;

/**
 * 状态根不一致事件（PLAN-014 Phase 2 / PLAN-012 A-3）。
 *
 * <p>由 {@code StateReplayManager} 在重放后校验发现"重放重建的状态根 !=
 * 区块声明根"时发布；由 {@code StateRootMismatchHandler} 异步消费，
 * 按配置 {@code nexus.core.state-root-mismatch.action} 决定处置：
 * {@code observe}（默认，仅日志/计数）或 {@code rollback}（回滚 + 拉黑）。</p>
 */
public class StateRootMismatchEvent extends ApplicationEvent {

    private final Block block;

    private final String reason;

    public StateRootMismatchEvent(Object source, Block block, String reason) {
        super(source);
        this.block = block;
        this.reason = reason;
    }

    public Block getBlock() {
        return block;
    }

    public String getReason() {
        return reason;
    }
}
