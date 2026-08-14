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

import org.nexus.core.validate.CompositeBlockRule;
import org.nexus.core.validate.MerkleRule;
import org.nexus.core.validate.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.nexus.db.StateDB;
import org.nexus.merkletree.MerkleTreeManager;

import java.util.List;

@Component
public class PendingBlocksManager {

    @Autowired
    private NexusChainBlockChain bc;

    @Autowired
    private StateDB stateDB;

    @Autowired
    private CompositeBlockRule rule;

    @Autowired
    private MerkleRule merkleRule;

    /** PLAN-003：分叉重组管理器（父块缺失 → 尝试切换更长链）。 */
    @Autowired(required = false)
    private ReorgManager reorgManager;

    private Logger logger = LoggerFactory.getLogger(PendingBlocksManager.class);

    @Autowired
    private MerkleTreeManager merkleTreeManager;

    // 区块的写入全部走这里
    @Async
    public void addPendingBlocks(BlocksCache cache) {
        while (true) {
            List<Block> chain = cache.popLongestChain();
            if (chain == null || chain.size() == 0){
                break;
            }
            if (chainHasWritten(chain)) {
                continue;
            }
            logger.info("try to write blocks to local storage, size = " + chain.size());
            Block lastConfirmed = stateDB.getLastConfirmed();
            for (Block b : chain) {
                if (b.nHeight <= lastConfirmed.nHeight || stateDB.hasBlockInCache(b.getHash())) {
                    logger.info("the block has written");
                    continue;
                }
                Result res = rule.validateBlock(b);
                if (!res.isSuccess()) {
                    // PLAN-003：父块缺失 → 可能是更长分叉链，交 ReorgManager 处理
                    if (res.getMessage() != null
                            && (res.getMessage().contains("failed to find parent")
                                || res.getMessage().contains("cannot find parent"))
                            && reorgManager != null) {
                        boolean switched = reorgManager.handlePotentialFork(b);
                        if (switched) {
                            logger.info("PendingBlocks: fork switch executed for block height={}", b.nHeight);
                            continue;
                        }
                    }
                    logger.error("validate the block fail error = " + res.getMessage());
                    return;
                }
                Result result = merkleRule.validateBlock(b);
                if (!result.isSuccess()) {
                    merkleTreeManager.writeBlockToCache(b);
                    continue;
                }
                b.weight = 1;
                stateDB.writeBlock(b);
            }
        }
    }

    private boolean chainHasWritten(List<Block> chain) {
        if (chain == null || chain.size() == 0) {
            return true;
        }
        byte[] hash = chain.get(chain.size() - 1).getHash();
        return stateDB.hasBlock(hash) || bc.hasBlock(hash);
    }
}
