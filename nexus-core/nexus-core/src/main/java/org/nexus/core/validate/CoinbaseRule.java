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
import org.nexus.command.TransactionCheck;
import org.nexus.consensus.pow.EconomicModel;
import org.nexus.db.StateDB;
import org.nexus.util.Arrays;
import org.nexus.core.Block;
import org.nexus.core.account.Transaction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

// Coinbase 校验规则
@Component
public class CoinbaseRule implements BlockRule, TransactionRule {

    @Autowired
    private StateDB stateDB;

    @Autowired
    private EconomicModel economicModel;

    public CoinbaseRule() {
    }

    @Override
    public Result validateBlock(Block block) {
        if(block.body == null || block.body.size() == 0){
            return Result.Error("missing block body");
        }
        // the first transaction of block must be coin base
        Transaction coinbase = block.body.get(0);
        if (coinbase == null) {
            return Result.Error("missing coin base transaction");
        }
        if (coinbase.type != Transaction.Type.COINBASE.ordinal()) {
            return Result.Error("the first transaction of block body must be coin base");
        }
        Block parent = stateDB.getBlock(block.hashPrevBlock);
        if (parent == null) {
            return Result.Error("cannot find parent" + Hex.encodeHexString(block.hashPrevBlock) + " " + (block.nHeight-1));
        }
        Result res = validateTransaction(coinbase);
        if (!res.isSuccess()) {
            return res;
        }

        long fees = 0;
        // the block body contains at most one coin base transaction
        for (int i = 1; i < block.body.size(); i++) {
            Transaction tx = block.body.get(i);
            if (tx.type == Transaction.Type.COINBASE.ordinal()) {
                return Result.Error("a block contains at most one coin base ");
            }
            // 手续费守恒（P0 修复，2026-11-06）：计入 coinbase 的手续费必须真的从
            // 发送方扣过款。只有共识白名单内的类型会在状态重放中扣手续费；其余类型
            // 会被 Miner 计入 coinbase 却不产生任何扣款，等于凭空增发。这里独立复算
            // 一遍"会产生扣款"的类型集合，与 TransactionCheck 的类型门禁交叉校验。
            if (!TransactionCheck.isConsensusEnabledType(tx.type)) {
                return Result.Error("fee conservation violated: transaction type 0x"
                        + Hex.encodeHexString(new byte[]{(byte) tx.type})
                        + " does not debit a fee (type not enabled)");
            }
            fees += tx.getFee();
        }

        // check amount = consensus amount + fees
        if (coinbase.amount != economicModel.getConsensusRewardAtHeight(block.nHeight) + fees) {
            return Result.Error("amount not equals to consensus reward plus fees");
        }
        return Result.SUCCESS;
    }

    // coin base transaction contains zero fields
    @Override
    public Result validateTransaction(Transaction transaction) {
        if (transaction == null) {
            return Result.Error("missing coin base transaction");
        }
        if (transaction.type != Transaction.Type.COINBASE.ordinal()) {
            return Result.Error("the first transaction of block body must be coin base");
        }
        if (!Arrays.areEqual(transaction.from, new byte[32])
                || transaction.gasPrice != 0
                || (transaction.payload != null && transaction.payload.length != 0)
                || !Arrays.areEqual(transaction.signature, new byte[64])
        ) {
            return Result.Error("coin base transaction has zero field other than null");
        }
        return Result.SUCCESS;
    }


}