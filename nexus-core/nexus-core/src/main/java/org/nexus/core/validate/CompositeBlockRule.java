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

import org.nexus.core.Block;
import org.nexus.core.account.Transaction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class CompositeBlockRule implements BlockRule {

    private List<BlockRule> rulers;

    /** NexusChain 支付扩展交易验证规则列表（实现 TransactionRule 接口）。 */
    private List<TransactionRule> transactionRules;

    @Autowired
    private BasicRule basicRule;

    @Autowired
    private AddressRule addressRule;

    @Autowired
    private CoinbaseRule coinbaseRule;

    @Autowired
    private ConsensusRule consensusRule;

    @Autowired
    private AccountRule accountRule;

    @Autowired
    private SignatureRule signatureRule;

    // === NexusChain 支付扩展验证规则 ===

    @Autowired
    private PaymentChannelRule paymentChannelRule;

    @Autowired
    private BatchTransferRule batchTransferRule;

    @Autowired
    private StableCoinRule stableCoinRule;

    @Autowired
    private BridgeRule bridgeRule;

    public void addRule(BlockRule... rules) {
        Collections.addAll(rulers, rules);
    }

    /**
     * 添加支付扩展交易验证规则。
     *
     * @param rules 待添加的 TransactionRule 实例
     */
    public void addTransactionRule(TransactionRule... rules) {
        Collections.addAll(transactionRules, rules);
    }

    @Override
    public Result validateBlock(Block block) {
        // 1. 执行所有区块级规则（BlockRule）
        for (BlockRule r : rulers) {
            Result res = r.validateBlock(block);
            if (!res.isSuccess()) {
                return res;
            }
        }
        // 2. 对区块内每笔交易执行支付扩展交易级规则（TransactionRule）
        if (block.body != null && transactionRules != null && !transactionRules.isEmpty()) {
            for (Transaction tx : block.body) {
                for (TransactionRule tr : transactionRules) {
                    Result res = tr.validateTransaction(tx);
                    if (!res.isSuccess()) {
                        return res;
                    }
                }
            }
        }
        return Result.SUCCESS;
    }

    public CompositeBlockRule() {
        rulers = new ArrayList<>();
        transactionRules = new ArrayList<>();
    }

    @PostConstruct
    public void init() {
        addRule(basicRule, addressRule, coinbaseRule, consensusRule, signatureRule, accountRule);
        // 注入 NexusChain 支付扩展交易验证规则
        addTransactionRule(paymentChannelRule, batchTransferRule, stableCoinRule, bridgeRule);
    }
}