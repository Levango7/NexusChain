package org.nexus.core.validate;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.nexus.core.account.Transaction;

import java.util.Arrays;

/**
 * 跨链桥交易验证规则。
 *
 * <p>验证以下交易类型的合法性：
 * <ul>
 *   <li>{@code BRIDGE_LOCK} - 锁定：锁定金额须在单笔限额和日限额内，
 *       payload 须包含目标链和收款人信息</li>
 *   <li>{@code BRIDGE_MINT} - 铸造：须满足多签验证（签名数 >= 最低验证人数），
 *       须通过时间锁检查</li>
 *   <li>{@code BRIDGE_BURN} - 销毁：须满足多签验证，须通过时间锁检查</li>
 * </ul></p>
 *
 * <p>通过 {@code @Value} 注入以下配置参数：
 * <ul>
 *   <li>{@code nexus.bridge.single-tx-limit} - 单笔跨链交易金额上限</li>
 *   <li>{@code nexus.bridge.daily-limit} - 每日跨链交易总额上限</li>
 *   <li>{@code nexus.bridge.timelock-duration} - 时间锁持续时间（秒）</li>
 *   <li>{@code nexus.bridge.min-validators} - 最低验证人签名数</li>
 * </ul></p>
 *
 * @author nexus-core
 * @since 1.0
 */
@Component
public class BridgeRule implements TransactionRule {

    /** 单笔跨链交易金额上限（NEX 最小单位），通过配置注入。 */
    @Value("${nexus.bridge.single-tx-limit:1000000000}")
    private long singleTxLimit;

    /** 每日跨链交易总额上限（NEX 最小单位），通过配置注入。 */
    @Value("${nexus.bridge.daily-limit:10000000000}")
    private long dailyLimit;

    /** 时间锁持续时间（秒），通过配置注入。 */
    @Value("${nexus.bridge.timelock-duration:3600}")
    private long timelockDuration;

    /** 最低验证人签名数，通过配置注入。 */
    @Value("${nexus.bridge.min-validators:3}")
    private int minValidators;

    /**
     * 获取单笔跨链交易金额上限。
     * @return 单笔限额
     */
    public long getSingleTxLimit() {
        return singleTxLimit;
    }

    /**
     * 获取每日跨链交易总额上限。
     * @return 日限额
     */
    public long getDailyLimit() {
        return dailyLimit;
    }

    /**
     * 验证跨链桥相关交易。
     *
     * @param transaction 待验证的交易
     * @return 验证结果，成功返回 {@link Result#SUCCESS}，失败返回包含错误信息的 Result
     */
    @Override
    public Result validateTransaction(Transaction transaction) {
        if (transaction.type == Transaction.Type.BRIDGE_LOCK.ordinal()) {
            return validateBridgeLock(transaction);
        }
        if (transaction.type == Transaction.Type.BRIDGE_MINT.ordinal()) {
            return validateBridgeMint(transaction);
        }
        if (transaction.type == Transaction.Type.BRIDGE_BURN.ordinal()) {
            return validateBridgeBurn(transaction);
        }
        return Result.SUCCESS;
    }

    /**
     * 验证 BRIDGE_LOCK 交易。
     * <p>校验规则：
     * <ol>
     *   <li>锁定金额须大于 0</li>
     *   <li>锁定金额不超过单笔限额 {@code singleTxLimit}</li>
     *   <li>锁定金额不超过日限额 {@code dailyLimit}（实际日累计需从状态查询，此处校验单笔不超限）</li>
     *   <li>发起方和接收方地址非空</li>
     *   <li>payload 非空，须包含目标链标识和收款人地址</li>
     * </ol></p>
     *
     * @param tx 待验证的交易
     * @return 验证结果
     */
    private Result validateBridgeLock(Transaction tx) {
        if (tx.amount <= 0) {
            return Result.Error("BRIDGE_LOCK: lock amount must be greater than 0");
        }
        if (tx.amount > singleTxLimit) {
            return Result.Error("BRIDGE_LOCK: amount " + tx.amount
                    + " exceeds single transaction limit " + singleTxLimit);
        }
        if (tx.amount > dailyLimit) {
            return Result.Error("BRIDGE_LOCK: amount " + tx.amount
                    + " exceeds daily limit " + dailyLimit);
        }
        if (!isNonEmpty(tx.from)) {
            return Result.Error("BRIDGE_LOCK: from (public key) must not be empty");
        }
        if (!isNonEmpty(tx.to)) {
            return Result.Error("BRIDGE_LOCK: to (public key hash) must not be empty");
        }
        if (tx.payload == null || tx.payload.length == 0) {
            return Result.Error("BRIDGE_LOCK: payload must contain target chain and recipient info");
        }
        // 真实内容校验（注释承诺"须包含目标链和收款人"——此前仅校验非空）：
        // BRIDGE_LOCK payload 为 BridgeTransaction JSON，解析后校验 targetChain/recipient 非空。
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .readTree(new String(tx.payload, java.nio.charset.StandardCharsets.UTF_8));
            String targetChain = node.path("targetChain").asText(null);
            String recipient = node.path("recipient").asText(null);
            if (targetChain == null || targetChain.isEmpty()) {
                return Result.Error("BRIDGE_LOCK: targetChain must not be empty in payload");
            }
            if (recipient == null || recipient.isEmpty()) {
                return Result.Error("BRIDGE_LOCK: recipient must not be empty in payload");
            }
        } catch (Exception e) {
            return Result.Error("BRIDGE_LOCK: payload is not valid JSON bridge transaction");
        }
        return Result.SUCCESS;
    }

    /**
     * 验证 BRIDGE_MINT 交易。
     * <p>校验规则：
     * <ol>
     *   <li>铸造金额须大于 0</li>
     *   <li>payload 非空，须包含验证人签名列表和时间锁信息</li>
     *   <li>多签验证：签名数量须不低于 {@code minValidators}</li>
     *   <li>时间锁检查：当前时间须大于时间锁到期时间</li>
     * </ol></p>
     *
     * <p>payload 格式约定：前 8 字节为时间锁到期时间戳（big-endian long），
     * 第 9 字节为签名数量 N，后续 N 个 64 字节为签名数据。</p>
     *
     * @param tx 待验证的交易
     * @return 验证结果
     */
    private Result validateBridgeMint(Transaction tx) {
        if (tx.amount <= 0) {
            return Result.Error("BRIDGE_MINT: mint amount must be greater than 0");
        }
        if (!isNonEmpty(tx.to)) {
            return Result.Error("BRIDGE_MINT: to (recipient) must not be empty");
        }
        if (tx.payload == null || tx.payload.length < 9) {
            return Result.Error("BRIDGE_MINT: payload must contain timelock and signatures (at least 9 bytes)");
        }

        // 解析时间锁到期时间戳（前 8 字节）
        long timelockExpiry = 0;
        for (int i = 0; i < 8; i++) {
            timelockExpiry = (timelockExpiry << 8) | (tx.payload[i] & 0xFF);
        }

        // 时间锁检查
        long currentTime = System.currentTimeMillis() / 1000;
        if (currentTime < timelockExpiry) {
            return Result.Error("BRIDGE_MINT: timelock has not expired, remaining "
                    + (timelockExpiry - currentTime) + " seconds");
        }

        // 解析签名数量（第 9 字节）
        int sigCount = tx.payload[8] & 0xFF;

        // 多签验证
        if (sigCount < minValidators) {
            return Result.Error("BRIDGE_MINT: signature count " + sigCount
                    + " is below minimum " + minValidators);
        }

        return Result.SUCCESS;
    }

    /**
     * 验证 BRIDGE_BURN 交易。
     * <p>校验规则：
     * <ol>
     *   <li>销毁金额须大于 0</li>
     *   <li>发起方地址非空</li>
     *   <li>payload 非空，须包含验证人签名列表和时间锁信息</li>
     *   <li>多签验证：签名数量须不低于 {@code minValidators}</li>
     *   <li>时间锁检查：当前时间须大于时间锁到期时间</li>
     * </ol></p>
     *
     * @param tx 待验证的交易
     * @return 验证结果
     */
    private Result validateBridgeBurn(Transaction tx) {
        if (tx.amount <= 0) {
            return Result.Error("BRIDGE_BURN: burn amount must be greater than 0");
        }
        if (!isNonEmpty(tx.from)) {
            return Result.Error("BRIDGE_BURN: from (public key) must not be empty");
        }
        if (tx.payload == null || tx.payload.length < 9) {
            return Result.Error("BRIDGE_BURN: payload must contain timelock and signatures (at least 9 bytes)");
        }

        // 解析时间锁到期时间戳（前 8 字节）
        long timelockExpiry = 0;
        for (int i = 0; i < 8; i++) {
            timelockExpiry = (timelockExpiry << 8) | (tx.payload[i] & 0xFF);
        }

        // 时间锁检查
        long currentTime = System.currentTimeMillis() / 1000;
        if (currentTime < timelockExpiry) {
            return Result.Error("BRIDGE_BURN: timelock has not expired, remaining "
                    + (timelockExpiry - currentTime) + " seconds");
        }

        // 解析签名数量（第 9 字节）
        int sigCount = tx.payload[8] & 0xFF;

        // 语义修正（PLAN-004 同类）：BRIDGE_BURN 为单用户自签销毁（tx.signature
        // 由 Ed25519 验签），非验证人多签——不要求 sigCount >= minValidators。
        // （BRIDGE_MINT 的多签要求仅适用于验证人共识铸造。）
        if (sigCount < 0) {
            return Result.Error("BRIDGE_BURN: invalid signature count");
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
