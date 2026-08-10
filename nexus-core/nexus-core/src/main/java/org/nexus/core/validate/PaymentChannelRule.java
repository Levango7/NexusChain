package org.nexus.core.validate;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.nexus.core.account.Transaction;

import java.util.Arrays;

/**
 * 支付通道交易验证规则。
 *
 * <p>验证以下交易类型的合法性：
 * <ul>
 *   <li>{@code CHANNEL_OPEN} - 开启支付通道：金额须大于 0，发起方和接收方地址非空，
 *       payload 须包含通道参数（锁定时间、参与方信息等）</li>
 *   <li>{@code CHANNEL_UPDATE} - 更新支付通道：金额须为 0，payload 须包含通道状态更新数据
 *       （双方最新余额、nonce 等）</li>
 *   <li>{@code CHANNEL_CLOSE} - 关闭支付通道：payload 须包含最终结算状态，
 *       并验证争议期是否已满足</li>
 * </ul></p>
 *
 * <p>通过 {@code @Value} 注入以下配置参数：
 * <ul>
 *   <li>{@code nexus.payment.channel.lock-time} - 通道默认锁定时间（秒）</li>
 *   <li>{@code nexus.payment.channel.dispute-period} - 争议期时长（秒）</li>
 *   <li>{@code nexus.payment.channel.min-funding} - 通道最小注资金额</li>
 * </ul></p>
 *
 * @author nexus-core
 * @since 1.0
 */
@Component
public class PaymentChannelRule implements TransactionRule {

    /** 通道默认锁定时间（秒），通过配置注入。 */
    @Value("${nexus.payment.channel.lock-time:3600}")
    private long lockTime;

    /** 争议期时长（秒），通过配置注入。 */
    @Value("${nexus.payment.channel.dispute-period:86400}")
    private long disputePeriod;

    /** 通道最小注资金额（NEX 最小单位），通过配置注入。 */
    @Value("${nexus.payment.channel.min-funding:1}")
    private long minFunding;

    /**
     * 验证支付通道相关交易。
     *
     * @param transaction 待验证的交易
     * @return 验证结果，成功返回 {@link Result#SUCCESS}，失败返回包含错误信息的 Result
     */
    @Override
    public Result validateTransaction(Transaction transaction) {
        if (transaction.type == Transaction.Type.CHANNEL_OPEN.ordinal()) {
            return validateChannelOpen(transaction);
        }
        if (transaction.type == Transaction.Type.CHANNEL_UPDATE.ordinal()) {
            return validateChannelUpdate(transaction);
        }
        if (transaction.type == Transaction.Type.CHANNEL_CLOSE.ordinal()) {
            return validateChannelClose(transaction);
        }
        return Result.SUCCESS;
    }

    /**
     * 验证 CHANNEL_OPEN 交易。
     * <p>校验规则：
     * <ol>
     *   <li>金额须大于 0，且不小于最小注资金额</li>
     *   <li>发起方（from）公钥非空</li>
     *   <li>接收方（to）公钥哈希非空</li>
     *   <li>payload 非空，须包含通道参数</li>
     * </ol></p>
     *
     * @param tx 待验证的交易
     * @return 验证结果
     */
    private Result validateChannelOpen(Transaction tx) {
        if (tx.amount <= 0) {
            return Result.Error("CHANNEL_OPEN: amount must be greater than 0");
        }
        if (tx.amount < minFunding) {
            return Result.Error("CHANNEL_OPEN: amount must be at least " + minFunding);
        }
        if (!isNonEmpty(tx.from)) {
            return Result.Error("CHANNEL_OPEN: from (public key) must not be empty");
        }
        if (!isNonEmpty(tx.to)) {
            return Result.Error("CHANNEL_OPEN: to (public key hash) must not be empty");
        }
        if (tx.payload == null || tx.payload.length == 0) {
            return Result.Error("CHANNEL_OPEN: payload must contain channel parameters");
        }
        return Result.SUCCESS;
    }

    /**
     * 验证 CHANNEL_UPDATE 交易。
     * <p>校验规则：
     * <ol>
     *   <li>金额须为 0（通道更新不涉及链上转账）</li>
     *   <li>payload 非空，须包含通道状态更新数据（最新余额、nonce 等）</li>
     * </ol></p>
     *
     * @param tx 待验证的交易
     * @return 验证结果
     */
    private Result validateChannelUpdate(Transaction tx) {
        if (tx.amount != 0) {
            return Result.Error("CHANNEL_UPDATE: amount must be 0");
        }
        if (tx.payload == null || tx.payload.length == 0) {
            return Result.Error("CHANNEL_UPDATE: payload must contain channel state update");
        }
        return Result.SUCCESS;
    }

    /**
     * 验证 CHANNEL_CLOSE 交易。
     * <p>校验规则：
     * <ol>
     *   <li>payload 非空，须包含最终结算状态（双方最终余额）</li>
     *   <li>验证争议期：交易 nonce 表示通道进入关闭状态的区块高度/时间戳，
     *       须确保已超过争议期 disputePeriod</li>
     * </ol></p>
     *
     * @param tx 待验证的交易
     * @return 验证结果
     */
    private Result validateChannelClose(Transaction tx) {
        if (tx.payload == null || tx.payload.length == 0) {
            return Result.Error("CHANNEL_CLOSE: payload must contain final settlement state");
        }
        // 验证争议期：nonce 存储通道关闭发起时间，当前时间须大于 nonce + disputePeriod
        long closeInitiatedAt = tx.nonce;
        long currentTime = System.currentTimeMillis() / 1000;
        if (currentTime < closeInitiatedAt + disputePeriod) {
            return Result.Error("CHANNEL_CLOSE: dispute period has not elapsed, remaining "
                    + (closeInitiatedAt + disputePeriod - currentTime) + " seconds");
        }
        return Result.SUCCESS;
    }

    /**
     * 检查字节数组是否非空（非 null 且不全为零）。
     *
     * @param bytes 待检查的字节数组
     * @return 如果数组非 null 且至少有一个非零字节则返回 true
     */
    private boolean isNonEmpty(byte[] bytes) {
        if (bytes == null) {
            return false;
        }
        byte[] zeros = new byte[bytes.length];
        return !Arrays.equals(bytes, zeros);
    }
}
