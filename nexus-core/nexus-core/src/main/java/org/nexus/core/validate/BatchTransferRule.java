package org.nexus.core.validate;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.nexus.core.account.Transaction;
import org.nexus.core.payment.BatchTransferPayload;
import org.nexus.core.payment.BatchTransferPayload.TransferItem;
import org.nexus.keystore.wallet.KeystoreAction;

import java.util.List;

/**
 * 批量转账验证规则。
 *
 * <p>验证 {@code BATCH_TRANSFER} 交易类型的合法性。批量转账允许在单笔交易中
 * 向多个收款人转账，payload 为 JSON 数组格式，包含多个收款地址和金额。</p>
 *
 * <p>校验规则：
 * <ol>
 *   <li>payload 可正确解析为转账项列表</li>
 *   <li>收款人数量不超过 {@code nexus.payment.batch.max-recipients} 配置上限</li>
 *   <li>转账总金额不超过 {@code nexus.payment.batch.max-total-amount} 配置上限</li>
 *   <li>每个收款人地址为合法的 NEX 地址</li>
 *   <li>每笔转账金额须大于 0</li>
 * </ol></p>
 *
 * @author nexus-core
 * @since 1.0
 */
@Component
public class BatchTransferRule implements TransactionRule {

    /** 最大收款人数量，通过配置注入。 */
    @Value("${nexus.payment.batch.max-recipients:100}")
    private int maxRecipients;

    /** 最大转账总金额（NEX 最小单位），通过配置注入。 */
    @Value("${nexus.payment.batch.max-total-amount:100000000000}")
    private long maxTotalAmount;

    /**
     * 验证批量转账交易。
     *
     * @param transaction 待验证的交易
     * @return 验证结果，成功返回 {@link Result#SUCCESS}，失败返回包含错误信息的 Result
     */
    @Override
    public Result validateTransaction(Transaction transaction) {
        if (transaction.type != Transaction.Type.BATCH_TRANSFER.ordinal()) {
            return Result.SUCCESS;
        }

        // 解析 payload
        List<TransferItem> items;
        try {
            items = BatchTransferPayload.parse(transaction.payload);
        } catch (Exception e) {
            return Result.Error("BATCH_TRANSFER: failed to parse payload: " + e.getMessage());
        }

        if (items.isEmpty()) {
            return Result.Error("BATCH_TRANSFER: payload must contain at least one transfer item");
        }

        // 验证收款人数量
        if (items.size() > maxRecipients) {
            return Result.Error("BATCH_TRANSFER: number of recipients " + items.size()
                    + " exceeds maximum " + maxRecipients);
        }

        // 验证每笔转账和总金额
        long totalAmount = 0;
        for (int i = 0; i < items.size(); i++) {
            TransferItem item = items.get(i);

            // 验证地址非空
            if (item.getAddress() == null || item.getAddress().isEmpty()) {
                return Result.Error("BATCH_TRANSFER: recipient address at index " + i + " is empty");
            }

            // 验证地址合法性
            if (KeystoreAction.verifyAddress(item.getAddress()) != 0) {
                return Result.Error("BATCH_TRANSFER: invalid recipient address at index " + i
                        + ": " + item.getAddress());
            }

            // 验证单笔金额大于 0
            if (item.getAmount() <= 0) {
                return Result.Error("BATCH_TRANSFER: amount at index " + i + " must be greater than 0");
            }

            totalAmount += item.getAmount();
        }

        // 验证总金额不超过上限
        if (totalAmount > maxTotalAmount) {
            return Result.Error("BATCH_TRANSFER: total amount " + totalAmount
                    + " exceeds maximum " + maxTotalAmount);
        }

        // 验证交易 amount 字段与总金额一致
        if (transaction.amount != totalAmount) {
            return Result.Error("BATCH_TRANSFER: transaction amount " + transaction.amount
                    + " does not match payload total " + totalAmount);
        }

        return Result.SUCCESS;
    }
}
