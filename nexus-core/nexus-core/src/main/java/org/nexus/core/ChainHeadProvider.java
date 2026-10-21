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

import org.nexus.db.StateDB;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 面向状态/同步接口的链头读取入口。
 *
 * <p>{@link NexusChainBlockChain#currentHeader()} 是数据库查询；PostgreSQL
 * 短时不可用时，P2P 状态广播和 {@code /consensus/status} 不应跟着失败。
 * 运行期链头已经保存在 {@link StateDB} 的内存缓存/已确认块中，因此状态类接口
 * 优先读内存，只有内存尚无链头（例如启动早期）时才回退到创世块。</p>
 *
 * <p>注意：这里只用于状态展示与同步握手，不用于交易验证或状态写入。
 * 账户/状态读取仍必须走数据库并保持 fail-closed。</p>
 */
@Component
public class ChainHeadProvider {

    @Autowired
    private StateDB stateDB;

    @Autowired
    private Block genesis;

    /**
     * 返回当前本地链头；内存中没有时返回创世块，避免状态接口因存储故障 500。
     */
    public Block currentHeader() {
        Block best = stateDB.getBestBlock();
        return best != null ? best : genesis;
    }
}
