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

package org.nexus.service;

import org.nexus.ApiResult.APIResult;
import org.nexus.core.payment.BatchTransferPayload;
import org.nexus.core.payment.BatchTransferPayload.TransferItem;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 批量转账业务服务。
 *
 * <p>支付扩展类型（0x10–0x1a）当前保留未启用：批量转账路由保留、
 * 入参校验保留，但不再构造 {@code BATCH_TRANSFER} 交易，统一返回
 * {@link APIResult#PAYMENT_EXTENSION_NOT_ENABLED}。</p>
 *
 * @author nexus-core
 * @since 1.0
 */
@Component
public class BatchTransferService {

    /** 最大收款人数量。 */
    private static final int MAX_RECIPIENTS = 256;

    /** 最大总金额（NEX 最小单位）。 */
    private static final long MAX_TOTAL_AMOUNT = Long.MAX_VALUE;

    /**
     * 提交批量转账交易。
     *
     * <p>支付扩展类型（0x10–0x1a）保留未启用：保留路由与入参校验，
     * 但不再构造 {@code BATCH_TRANSFER} 交易。</p>
     *
     * @param fromPubkey 发起方公钥（十六进制字符串，32 字节）
     * @param recipients 收款人列表，每项包含 address 和 amount
     * @param prikey     发起方私钥（十六进制字符串，32 字节）
     * @param nonce      交易 nonce
     * @return 统一响应结果；支付扩展未启用时返回 {@link APIResult#PAYMENT_EXTENSION_NOT_ENABLED}
     */
    public APIResult batchTransfer(String fromPubkey, List<TransferItem> recipients,
                                   String prikey, long nonce) {
        if (fromPubkey == null || fromPubkey.isEmpty()) {
            return APIResult.newFailResult(APIResult.FAIL, "fromPubkey is required");
        }
        if (recipients == null || recipients.isEmpty()) {
            return APIResult.newFailResult(APIResult.FAIL, "recipients must not be empty");
        }
        if (prikey == null || prikey.isEmpty()) {
            return APIResult.newFailResult(APIResult.FAIL, "prikey is required");
        }

        try {
            BatchTransferPayload.validate(recipients, MAX_RECIPIENTS, MAX_TOTAL_AMOUNT);
        } catch (IllegalArgumentException e) {
            return APIResult.newFailResult(APIResult.FAIL, "Validation failed: " + e.getMessage());
        }

        // 支付扩展（0x10–0x1a）保留未启用：不再构造 BATCH_TRANSFER 交易。
        return APIResult.paymentExtensionNotEnabled();
    }

    /**
     * 查询批量转账状态。
     *
     * <p>支付扩展类型当前保留未启用，本接口固定返回
     * {@link APIResult#PAYMENT_EXTENSION_NOT_ENABLED}，不再返回模拟 PENDING/UNKNOWN 状态。</p>
     *
     * @param txHash 交易哈希（十六进制字符串）
     * @return 统一响应结果；支付扩展未启用时返回 {@link APIResult#PAYMENT_EXTENSION_NOT_ENABLED}
     */
    public APIResult getBatchStatus(String txHash) {
        try {
            if (txHash == null || txHash.isEmpty()) {
                return APIResult.newFailResult(APIResult.FAIL, "txHash is required");
            }

            // 支付扩展（0x10–0x1a）保留未启用：不再返回模拟批量转账状态。
            return APIResult.paymentExtensionNotEnabled();
        } catch (RuntimeException e) {
            return APIResult.newFailResult(APIResult.FAIL, "Failed to query batch transfer status: " + e.getMessage());
        }
    }
}
