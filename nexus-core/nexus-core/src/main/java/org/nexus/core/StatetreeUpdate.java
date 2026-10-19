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

package org.nexus.core;

import org.nexus.core.event.NewBestBlockEvent;
import org.nexus.core.event.AccountUpdateFailedEvent;
import org.nexus.core.event.AccountUpdatedEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import org.nexus.pool.PeningTransPool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class StatetreeUpdate implements ApplicationListener<NewBestBlockEvent> {

    private static final Logger log = LoggerFactory.getLogger(StatetreeUpdate.class);
    @Autowired
    ApplicationContext ctx;

    @Autowired
    PeningTransPool peningTransPool;

    @Autowired
    StateReplayManager stateReplayManager;

    @Override
    public void onApplicationEvent(NewBestBlockEvent event) {
        Block b = event.getBlock();
        peningTransPool.updatePool(b.body, 2, b.nHeight);

        try {
            // PLAN-014 Phase 1：重放 + 持久化 + 重放后状态根校验统一收敛到
            // StateReplayManager。校验不一致目前为观察位（StateReplayManager 内
            // ERROR 日志 + 计数），不阻断确认；Phase 2 再接入回滚与对端拉黑。
            stateReplayManager.replayBlock(b);
        } catch (RuntimeException | java.io.IOException | org.apache.commons.codec.DecoderException e) {
            // CRITICAL FIX: previously returned silently without publishing any event,
            // causing StateDB.writeBlock's while(pendingBlock != null) to spin forever
            // holding the global write lock -> node permanently stuck.
            log.error("StatetreeUpdate: uncaught exception", e);
            ctx.publishEvent(new AccountUpdateFailedEvent(this, b,
                    "state tree update failed: " + e.getMessage()));
            return;
        }
        ctx.publishEvent(new AccountUpdatedEvent(this, b));
    }
}
