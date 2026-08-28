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
import org.bouncycastle.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.nexus.core.OrphanBlocksManager;
import org.nexus.core.NexusChainBlockChain;
import org.nexus.db.StateDB;
import org.nexus.encoding.BigEndian;
import org.nexus.encoding.JSONEncodeDecoder;
import org.nexus.core.Block;
import org.nexus.core.account.Account;
import org.nexus.core.account.Transaction;
import org.nexus.core.incubator.Incubator;
import org.hibernate.validator.HibernateValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.validation.Validation;
import java.util.List;
import java.util.Map;

// 基本规则校验 校验区块版本号，字段类型, pow，交易 merkle root
@Component
public class BasicRule implements BlockRule, TransactionRule {

    @Autowired
    private NexusChainBlockChain bc;

    @Autowired
    private StateDB stateDB;

    @Value("${nexus.consensus.block-interval}")
    private int blockInterval;

    private Block genesis;
    private static jakarta.validation.Validator validator = Validation.byProvider(HibernateValidator.class)
            .configure()
            .failFast(true)
            .buildValidatorFactory().getValidator();
    private static final Logger logger = LoggerFactory.getLogger(BasicRule.class);
    private static final JSONEncodeDecoder codec = new JSONEncodeDecoder();

    @Value("${p2p.max-blocks-per-transfer}")
    private int orphanHeightsRange;

    /**
     * 共识模式（PLAN-001 缺口 B）：pow/dpos 走 PoW 难度校验；
     * pos 模式跳过（PoS 区块由引擎 Ed25519 验签，无 nBits 难度）。
     * 审计修复：默认值对齐主配置/ConsensusConfig 的 dpos（原为 pow，
     * 属性缺失时各组件对共识模式认知分裂）。
     */
    @Value("${nexus.consensus.mode:dpos}")
    private String consensusMode;

    /** 是否为 PoS 共识模式。 */
    private boolean isPosMode() {
        return "pos".equalsIgnoreCase(consensusMode);
    }

    @Override
    public Result validateBlock(Block block) {
        Block best = stateDB.getBestBlock();
        if (block == null) {
            return Result.Error("null block");
        }
        if (Math.abs(best.nHeight - block.nHeight) > orphanHeightsRange) {
            return Result.Error("the block height " + block.nHeight + " is too small or too large, current height is " + best.nHeight);
        }
        // 区块基本校验 字段值非空
        if (validator.validate(block).size() != 0) {
            return Result.Error(validator.validate(block).toArray()[0].toString());
        }
        // 区块时间戳必须在一个周期的时间内
        if (block.nTime - System.currentTimeMillis() / 1000 > blockInterval) {
            return Result.Error("the received block timestamp too large");
        }
        // 区块大小限制
        if (block.size() > Block.MAX_BLOCK_SIZE) {
            return Result.Error("block size exceed");
        }
        // 不可以接收创世区块
        if (block.nHeight == 0 || Arrays.areEqual(block.getHash(), genesis.getHash())) {
            return Result.Error("cannot write genesis block");
        }
        // 区块体不可以为空
        if (block.body == null || block.body.size() == 0) {
            return Result.Error("missing body");
        }
        // 区块版本
        if (block.nVersion != genesis.nVersion) {
            return Result.Error("version check fail");
        }
        // PoW 难度校验（PLAN-001 缺口 B 适配：仅 pow/pow 模式执行；
        // PoS 区块由 PosConsensusEngine.validate 做 Ed25519 签名校验，
        // 无 nBits 难度值，此处跳过避免误拒跨节点传播的合法 PoS 块）
        if (!isPosMode() && BigEndian.compareUint256(Block.calculatePOWHash(block), block.nBits) >= 0) {
            return Result.Error("pow validate fail");
        }
        for (Transaction tx : block.body) {
            Result r = validateTransaction(tx);
            if (!r.isSuccess()) {
                return r;
            }
        }
        return Result.SUCCESS;
    }

    @Override
    public Result validateTransaction(Transaction transaction) {
        if (validator.validate(transaction).size() != 0 || transaction.version != Transaction.DEFAULT_TRANSACTION_VERSION) {
            return Result.Error(validator.validate(transaction).toArray()[0].toString() + "missing fields or version invalid");
        }
        // 1. deposit 事务的 amount 必须为 0
        if (transaction.type == Transaction.Type.DEPOSIT.ordinal() && transaction.amount != 0) {
            return Result.Error("the amount of deposit must be zero");
        }

        // === NexusChain 支付扩展交易基本验证 ===

        // 支付通道交易基本验证
        if (transaction.isChannelTransaction()) {
            if (transaction.type == Transaction.Type.CHANNEL_OPEN.ordinal() && transaction.amount <= 0) {
                return Result.Error("channel open amount must be positive");
            }
            if (transaction.type == Transaction.Type.CHANNEL_UPDATE.ordinal() && transaction.amount != 0) {
                return Result.Error("channel update amount must be zero");
            }
            if (!transaction.hasPayload()) {
                return Result.Error("channel transaction must have payload");
            }
        }

        // 批量转账基本验证
        if (transaction.type == Transaction.Type.BATCH_TRANSFER.ordinal()) {
            if (!transaction.hasPayload()) {
                return Result.Error("batch transfer must have payload");
            }
            if (transaction.amount != 0) {
                return Result.Error("batch transfer amount field must be zero (amounts in payload)");
            }
        }

        // 稳定币交易基本验证
        if (transaction.isStableCoinTransaction()) {
            if (transaction.type == Transaction.Type.MINT_STABLECOIN.ordinal() && transaction.amount <= 0) {
                return Result.Error("mint stablecoin collateral amount must be positive");
            }
            if (!transaction.hasPayload()) {
                return Result.Error("stablecoin transaction must have payload");
            }
        }

        // 跨链桥交易基本验证
        if (transaction.isBridgeTransaction()) {
            if (transaction.type == Transaction.Type.BRIDGE_LOCK.ordinal() && transaction.amount <= 0) {
                return Result.Error("bridge lock amount must be positive");
            }
            if (!transaction.hasPayload()) {
                return Result.Error("bridge transaction must have payload");
            }
        }

        // DID 身份注册基本验证
        if (transaction.type == Transaction.Type.IDENTITY_REGISTER.ordinal()) {
            if (transaction.amount < 0) {
                return Result.Error("identity register amount must be non-negative");
            }
            if (!transaction.hasPayload()) {
                return Result.Error("identity register must have payload");
            }
        }

        // 订阅授权基本验证
        if (transaction.type == Transaction.Type.SUBSCRIPTION_AUTH.ordinal()) {
            if (transaction.amount < 0) {
                return Result.Error("subscription auth amount must be non-negative");
            }
            if (!transaction.hasPayload()) {
                return Result.Error("subscription auth must have payload");
            }
        }

        return Result.SUCCESS;
    }

    @Autowired
    public BasicRule(Block genesis, @Value("${node-character}") String character) {
        this.genesis = genesis;
    }
}
