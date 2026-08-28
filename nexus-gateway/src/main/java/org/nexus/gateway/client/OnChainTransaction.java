package org.nexus.gateway.client;

import java.math.BigDecimal;

/**
 * 链上交易详情（P0-5 修复，v2.27.0）。
 *
 * <p>表示从链节点查询到的交易完整信息，用于支付确认时校验交易-订单绑定：
 * 交易金额必须与订单金额一致，交易收款人必须与商户结算地址一致。</p>
 *
 * <p>字段可能为 null（链节点不支持返回该字段时），调用方需做 null 检查。</p>
 *
 * @param txHash      交易哈希
 * @param amount      交易金额（最小单位）
 * @param tokenSymbol 代币符号
 * @param sender      发送方（20 字节公钥哈希小写 hex）
 * @param recipient   接收方（20 字节公钥哈希小写 hex，与 WalletUtils.addressToPubkeyHash 同一编码空间）
 * @param confirmed   是否已确认
 * @param blockHeight 所在区块高度（未确认时为 null）
 */
public record OnChainTransaction(
        String txHash,
        BigDecimal amount,
        String tokenSymbol,
        String sender,
        String recipient,
        Boolean confirmed,
        Long blockHeight
) {
}