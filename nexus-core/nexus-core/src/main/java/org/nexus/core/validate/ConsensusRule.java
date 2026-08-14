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

package org.nexus.core.validate;

import org.apache.commons.codec.binary.Hex;
import org.nexus.consensus.pow.ConsensusConfig;
import org.nexus.consensus.pow.Proposer;
import org.nexus.consensus.pow.TargetState;
import org.nexus.core.state.EraLinkedStateFactory;
import org.nexus.db.StateDB;
import org.nexus.encoding.BigEndian;
import org.nexus.core.Block;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;

// 共识校验规则
// 1. 目标值符合
// 2. 不是孤块
// 3. 区块高度正确
// 4. 时间戳递增
@Component
public class ConsensusRule implements BlockRule {
    private EraLinkedStateFactory targetStateFactory;

    @Autowired
    ConsensusConfig consensusConfig;

    private StateDB stateDB;

    @Autowired
    public ConsensusRule(StateDB stateDB) {
        this.stateDB = stateDB;
        this.targetStateFactory = stateDB.getTargetStateFactory();
    }

    /**
     * 共识模式（PLAN-002）：pow/dpos 走 DPoS proposer 时间表 + 难度校验；
     * pos 模式跳过（PoS 区块由引擎 Ed25519 验签，无 proposer 时间表/nBits）。
     */
    @org.springframework.beans.factory.annotation.Value("${nexus.consensus.mode:pow}")
    private String consensusMode;

    /** 是否为 PoS 共识模式。 */
    private boolean isPosMode() {
        return "pos".equalsIgnoreCase(consensusMode);
    }

    @Override
    public Result validateBlock(Block block) {
        Block parent = stateDB.getBlock(block.hashPrevBlock);
        // 不接受孤块
        if (parent == null) {
            return Result.Error("failed to find parent block");
        }
        // 父区块高度增1
        if (parent.nHeight + 1 != block.nHeight) {
            return Result.Error("block height invalid");
        }
        // PLAN-002 适配：PoS 模式跳过 DPoS proposer 时间表与难度值校验
        // （PoS 区块无 proposer 时间表/nBits 难度，由 PosConsensusEngine.validate
        //  做 Ed25519 签名校验；本规则链仅保留结构校验）
        if (isPosMode()) {
            return Result.SUCCESS;
        }
        // 出块在是否在合理时间内出块
        Optional<Proposer> p = stateDB.getProposersFactory().getProposer(parent, block.nTime);
        if (!p.
                map(x -> x.pubkeyHash.equals(Hex.encodeHexString(block.body.get(0).to)))
                .orElse(false)) {
            return Result.Error("the proposer cannot propose this block");
        }
        // 难度值符合调整难度值
        TargetState state = (TargetState) targetStateFactory.getInstance(block);
        if (BigEndian.decodeUint256(block.nBits).compareTo(state.getTarget()) != 0) {
            return Result.Error("block at height " + block.nHeight + " nbits invalid " + Hex.encodeHexString(BigEndian.encodeUint256(state.getTarget())) + " expected " + Hex.encodeHexString(block.nBits) + " received");
        }
        return Result.SUCCESS;
    }

    public ConsensusRule() {
    }
}
