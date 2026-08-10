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

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.nexus.ApiResult.APIResult;
import org.nexus.core.account.Transaction;
import org.nexus.core.TransactionPool;
import org.nexus.core.payment.BatchTransferPayload;
import org.nexus.core.payment.BatchTransferPayload.TransferItem;
import org.nexus.crypto.CryptoException;
import org.nexus.crypto.ed25519.Ed25519PrivateKey;
import org.nexus.keystore.crypto.RipemdUtility;
import org.nexus.keystore.crypto.SHA3Utility;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批量转账业务服务。
 *
 * <p>支持在单笔 {@code BATCH_TRANSFER} 交易中向多个收款人转账。
 * 本服务负责校验转账项、构造 payload、签名交易并提交到
 * {@link TransactionPool}。</p>
 *
 * <p>payload 格式由 {@link BatchTransferPayload#build(List)} 定义，
 * 包含 total_count、total_amount 和 items 三个字段。</p>
 *
 * @author nexus-core
 * @since 1.0
 */
@Component
public class BatchTransferService {

    /** 交易版本号，固定为 1。 */
    private static final int TX_VERSION = Transaction.DEFAULT_TRANSACTION_VERSION;

    /** 默认 gasPrice，按最小单位计费。 */
    private static final long DEFAULT_GAS_PRICE = 1L;

    /** 最大收款人数量。 */
    private static final int MAX_RECIPIENTS = 256;

    /** 最大总金额（NEX 最小单位）。 */
    private static final long MAX_TOTAL_AMOUNT = Long.MAX_VALUE;

    @Autowired
    private TransactionPool txPool;

    /**
     * 提交批量转账交易。
     *
     * <p>流程：
     * <ol>
     *   <li>调用 {@link BatchTransferPayload#build(List)} 构造 payload</li>
     *   <li>调用 {@link BatchTransferPayload#validate(List, int, long)} 校验</li>
     *   <li>使用私钥对交易签名</li>
     *   <li>构造 {@code BATCH_TRANSFER} 类型交易并提交到交易池</li>
     * </ol></p>
     *
     * @param fromPubkey 发起方公钥（十六进制字符串，32 字节）
     * @param recipients 收款人列表，每项包含 address 和 amount
     * @param prikey     发起方私钥（十六进制字符串，32 字节）
     * @param nonce      交易 nonce
     * @return 统一响应结果，data 中包含 txHash、recipientCount、totalAmount 等
     */
    public APIResult batchTransfer(String fromPubkey, List<TransferItem> recipients,
                                   String prikey, long nonce) {
        try {
            if (fromPubkey == null || fromPubkey.isEmpty()) {
                return APIResult.newFailResult(APIResult.FAIL, "fromPubkey is required");
            }
            if (recipients == null || recipients.isEmpty()) {
                return APIResult.newFailResult(APIResult.FAIL, "recipients must not be empty");
            }
            if (prikey == null || prikey.isEmpty()) {
                return APIResult.newFailResult(APIResult.FAIL, "prikey is required");
            }

            // 校验转账项
            BatchTransferPayload.validate(recipients, MAX_RECIPIENTS, MAX_TOTAL_AMOUNT);

            // 计算 from/to 字段
            byte[] fromBytes = Hex.decodeHex(fromPubkey.toCharArray());
            if (fromBytes.length != Transaction.PUBLIC_KEY_SIZE) {
                return APIResult.newFailResult(APIResult.FAIL,
                        "fromPubkey must be " + Transaction.PUBLIC_KEY_SIZE + " bytes, got " + fromBytes.length);
            }
            byte[] toBytes = RipemdUtility.ripemd160(SHA3Utility.keccak256(fromBytes));

            // 构造 payload
            byte[] payload = BatchTransferPayload.build(recipients);
            long totalAmount = BatchTransferPayload.getTotalAmount(recipients);

            // 占位签名（待签名）
            byte[] emptySig = new byte[Transaction.SIGNATURE_SIZE];

            Transaction tx = new Transaction(
                    TX_VERSION,
                    Transaction.Type.BATCH_TRANSFER.ordinal(),
                    nonce,
                    fromBytes,
                    DEFAULT_GAS_PRICE,
                    totalAmount,
                    payload,
                    toBytes,
                    emptySig
            );

            // 使用私钥签名
            byte[] prikeyBytes = Hex.decodeHex(prikey.toCharArray());
            Ed25519PrivateKey privateKey = new Ed25519PrivateKey(prikeyBytes);
            byte[] signature = privateKey.sign(tx.getRawForSign());
            tx.signature = signature;

            txPool.add(tx);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("txHash", tx.getHashHexString());
            data.put("recipientCount", recipients.size());
            data.put("totalAmount", totalAmount);
            data.put("status", "PENDING");

            return APIResult.newSuccess(data);
        } catch (DecoderException e) {
            return APIResult.newFailResult(APIResult.FAIL, "Invalid hex format: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return APIResult.newFailResult(APIResult.FAIL, "Validation failed: " + e.getMessage());
        } catch (CryptoException e) {
            return APIResult.newFailResult(APIResult.FAIL, "Signing failed: " + e.getMessage());
        } catch (Exception e) {
            return APIResult.newFailResult(APIResult.FAIL, "Failed to submit batch transfer: " + e.getMessage());
        }
    }

    /**
     * 查询批量转账状态。
     *
     * <p>骨架实现：返回 UNKNOWN 状态。后续可从交易池或区块链中
     * 查询交易的确认状态。</p>
     *
     * @param txHash 交易哈希（十六进制字符串）
     * @return 统一响应结果，data 中包含交易状态信息
     */
    public APIResult getBatchStatus(String txHash) {
        try {
            if (txHash == null || txHash.isEmpty()) {
                return APIResult.newFailResult(APIResult.FAIL, "txHash is required");
            }

            // 骨架：先查交易池，未命中则返回 UNKNOWN
            Map<String, Object> statusInfo = new LinkedHashMap<>();
            statusInfo.put("txHash", txHash);
            if (txPool != null && txPool.has(txHash)) {
                statusInfo.put("status", "PENDING");
                statusInfo.put("confirmations", 0);
            } else {
                statusInfo.put("status", "UNKNOWN");
                statusInfo.put("confirmations", 0);
            }

            return APIResult.newSuccess(statusInfo);
        } catch (Exception e) {
            return APIResult.newFailResult(APIResult.FAIL, "Failed to query batch transfer status: " + e.getMessage());
        }
    }
}
