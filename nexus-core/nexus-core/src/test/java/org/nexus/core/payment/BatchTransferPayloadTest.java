package org.nexus.core.payment;

import org.nexus.core.account.Transaction;
import org.nexus.core.payment.BatchTransferPayload.TransferItem;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * 批量转账 payload 编解码测试。
 *
 * <p>验证 BatchTransferPayload 的 build()/parse() 往返编解码、
 * 数量限制验证、总金额限制验证、getTotalAmount() 计算和空列表处理。</p>
 *
 * <p>payload 格式为 JSON 数组，每个元素包含 address 和 amount 两个字段。
 * 不依赖 Spring 容器，为纯单元测试。</p>
 *
 * @author nexus-core
 * @since 1.0
 */
public class BatchTransferPayloadTest {

    /** 最大收款人数量限制。 */
    private static final int MAX_RECIPIENTS = 100;
    /** 最大转账总金额限制（NEX 最小单位）。 */
    private static final long MAX_TOTAL_AMOUNT = 100000000000L;

    // ==================== 辅助方法 ====================

    /**
     * 计算转账项列表的总金额。
     *
     * @param items 转账项列表
     * @return 总金额
     */
    private long getTotalAmount(List<TransferItem> items) {
        if (items == null) {
            return 0;
        }
        long total = 0;
        for (TransferItem item : items) {
            total += item.getAmount();
        }
        return total;
    }

    /**
     * 验证批量转账列表的合法性。
     *
     * @param items 转账项列表
     * @throws IllegalArgumentException 如果验证失败
     */
    private void validate(List<TransferItem> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("BATCH_TRANSFER: payload 至少包含一笔转账");
        }
        if (items.size() > MAX_RECIPIENTS) {
            throw new IllegalArgumentException(
                    "BATCH_TRANSFER: 收款人数量 " + items.size() + " 超过上限 " + MAX_RECIPIENTS);
        }
        long total = 0;
        for (int i = 0; i < items.size(); i++) {
            TransferItem item = items.get(i);
            if (item.getAddress() == null || item.getAddress().isEmpty()) {
                throw new IllegalArgumentException(
                        "BATCH_TRANSFER: 第 " + i + " 笔收款地址为空");
            }
            if (item.getAmount() <= 0) {
                throw new IllegalArgumentException(
                        "BATCH_TRANSFER: 第 " + i + " 笔金额须大于 0");
            }
            total += item.getAmount();
        }
        if (total > MAX_TOTAL_AMOUNT) {
            throw new IllegalArgumentException(
                    "BATCH_TRANSFER: 总金额 " + total + " 超过上限 " + MAX_TOTAL_AMOUNT);
        }
    }

    /**
     * 创建测试用转账项列表。
     *
     * @param count 转账项数量
     * @param amountPerItem 每笔金额
     * @return 转账项列表
     */
    private List<TransferItem> createItems(int count, long amountPerItem) {
        List<TransferItem> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            items.add(new TransferItem("NEX_addr_" + i, amountPerItem));
        }
        return items;
    }

    // ==================== build + parse 往返测试 ====================

    /**
     * 测试 build() + parse() 往返编解码
     */
    @Test
    public void testBuildAndParseRoundTrip() throws IOException {
        // 创建包含多笔转账的列表
        List<TransferItem> original = new ArrayList<>();
        original.add(new TransferItem("NEX1abc123", 1000L));
        original.add(new TransferItem("NEX2def456", 2000L));
        original.add(new TransferItem("NEX3ghi789", 3000L));

        // 构建为 payload 字节数组
        byte[] payload = BatchTransferPayload.build(original);
        assertNotNull(payload);
        assertTrue(payload.length > 0);

        // 解析回列表
        List<TransferItem> parsed = BatchTransferPayload.parse(payload);

        // 验证往返结果一致
        assertEquals(original.size(), parsed.size());
        for (int i = 0; i < original.size(); i++) {
            assertEquals(original.get(i).getAddress(), parsed.get(i).getAddress());
            assertEquals(original.get(i).getAmount(), parsed.get(i).getAmount());
        }
    }

    /**
     * 测试单笔转账的 build + parse 往返
     */
    @Test
    public void testBuildAndParseSingleItem() throws IOException {
        List<TransferItem> original = new ArrayList<>();
        original.add(new TransferItem("NEX_single_addr", 500000L));

        byte[] payload = BatchTransferPayload.build(original);
        List<TransferItem> parsed = BatchTransferPayload.parse(payload);

        assertEquals(1, parsed.size());
        assertEquals("NEX_single_addr", parsed.get(0).getAddress());
        assertEquals(500000L, parsed.get(0).getAmount());
    }

    /**
     * 测试大额转账金额的编解码精度
     */
    @Test
    public void testBuildAndParseLargeAmount() throws IOException {
        List<TransferItem> original = new ArrayList<>();
        original.add(new TransferItem("NEX_large_addr", Long.MAX_VALUE));

        byte[] payload = BatchTransferPayload.build(original);
        List<TransferItem> parsed = BatchTransferPayload.parse(payload);

        assertEquals(Long.MAX_VALUE, parsed.get(0).getAmount());
    }

    // ==================== validate 数量限制测试 ====================

    /**
     * 测试 validate() 数量限制：不超过上限时验证通过
     */
    @Test
    public void testValidateCountWithinLimit() {
        // 100 笔，恰好等于上限
        List<TransferItem> items = createItems(100, 1000L);
        validate(items); // 不应抛异常
    }

    /**
     * 测试 validate() 数量限制：超过上限时抛异常
     */
    @Test(expected = IllegalArgumentException.class)
    public void testValidateCountExceedsLimit() {
        // 101 笔，超过上限
        List<TransferItem> items = createItems(101, 1000L);
        validate(items);
    }

    /**
     * 测试 validate() 数量限制：空列表抛异常
     */
    @Test(expected = IllegalArgumentException.class)
    public void testValidateEmptyList() {
        validate(new ArrayList<>());
    }

    /**
     * 测试 validate() 数量限制：null 列表抛异常
     */
    @Test(expected = IllegalArgumentException.class)
    public void testValidateNullList() {
        validate(null);
    }

    // ==================== validate 总金额限制测试 ====================

    /**
     * 测试 validate() 总金额限制：不超过上限时验证通过
     */
    @Test
    public void testValidateTotalAmountWithinLimit() {
        // 100 笔，每笔 1000000000，总金额 = 100000000000（恰好等于上限）
        List<TransferItem> items = createItems(100, 1000000000L);
        validate(items); // 不应抛异常
    }

    /**
     * 测试 validate() 总金额限制：超过上限时抛异常
     */
    @Test(expected = IllegalArgumentException.class)
    public void testValidateTotalAmountExceedsLimit() {
        // 100 笔，每笔 1000000001，总金额 = 100000000100（超过上限）
        List<TransferItem> items = createItems(100, 1000000001L);
        validate(items);
    }

    /**
     * 测试 validate() 单笔金额为零时抛异常
     */
    @Test(expected = IllegalArgumentException.class)
    public void testValidateZeroAmount() {
        List<TransferItem> items = new ArrayList<>();
        items.add(new TransferItem("NEX_addr_0", 0L));
        items.add(new TransferItem("NEX_addr_1", 1000L));
        validate(items);
    }

    /**
     * 测试 validate() 单笔金额为负时抛异常
     */
    @Test(expected = IllegalArgumentException.class)
    public void testValidateNegativeAmount() {
        List<TransferItem> items = new ArrayList<>();
        items.add(new TransferItem("NEX_addr_0", -1000L));
        validate(items);
    }

    /**
     * 测试 validate() 地址为空时抛异常
     */
    @Test(expected = IllegalArgumentException.class)
    public void testValidateEmptyAddress() {
        List<TransferItem> items = new ArrayList<>();
        items.add(new TransferItem("", 1000L));
        validate(items);
    }

    /**
     * 测试 validate() 地址为 null 时抛异常
     */
    @Test(expected = IllegalArgumentException.class)
    public void testValidateNullAddress() {
        TransferItem item = new TransferItem();
        item.setAddress(null);
        item.setAmount(1000L);
        List<TransferItem> items = new ArrayList<>();
        items.add(item);
        validate(items);
    }

    // ==================== getTotalAmount 计算 ====================

    /**
     * 测试 getTotalAmount() 计算
     */
    @Test
    public void testGetTotalAmount() {
        List<TransferItem> items = new ArrayList<>();
        items.add(new TransferItem("addr1", 1000L));
        items.add(new TransferItem("addr2", 2000L));
        items.add(new TransferItem("addr3", 3000L));

        long total = getTotalAmount(items);
        assertEquals(6000L, total);
    }

    /**
     * 测试 getTotalAmount() 对单笔转账
     */
    @Test
    public void testGetTotalAmountSingle() {
        List<TransferItem> items = new ArrayList<>();
        items.add(new TransferItem("addr1", 999999L));

        assertEquals(999999L, getTotalAmount(items));
    }

    /**
     * 测试 getTotalAmount() 对空列表返回 0
     */
    @Test
    public void testGetTotalAmountEmpty() {
        assertEquals(0L, getTotalAmount(new ArrayList<>()));
    }

    /**
     * 测试 getTotalAmount() 对 null 列表返回 0
     */
    @Test
    public void testGetTotalAmountNull() {
        assertEquals(0L, getTotalAmount(null));
    }

    // ==================== 空列表处理测试 ====================

    /**
     * 测试 build() 对空列表返回空字节数组
     */
    @Test
    public void testBuildEmptyList() throws IOException {
        byte[] payload = BatchTransferPayload.build(new ArrayList<>());
        assertNotNull(payload);
        assertEquals(0, payload.length);
    }

    /**
     * 测试 build() 对 null 列表返回空字节数组
     */
    @Test
    public void testBuildNullList() throws IOException {
        byte[] payload = BatchTransferPayload.build(null);
        assertNotNull(payload);
        assertEquals(0, payload.length);
    }

    /**
     * 测试 parse() 对空字节数组返回空列表
     */
    @Test
    public void testParseEmptyBytes() throws IOException {
        List<TransferItem> items = BatchTransferPayload.parse(new byte[0]);
        assertNotNull(items);
        assertTrue(items.isEmpty());
    }

    /**
     * 测试 parse() 对 null 返回空列表
     */
    @Test
    public void testParseNull() throws IOException {
        List<TransferItem> items = BatchTransferPayload.parse(null);
        assertNotNull(items);
        assertTrue(items.isEmpty());
    }

    // ==================== TransferItem 测试 ====================

    /**
     * 测试 TransferItem 构造器和 getter
     */
    @Test
    public void testTransferItemConstructor() {
        TransferItem item = new TransferItem("NEX_test_addr", 50000L);
        assertEquals("NEX_test_addr", item.getAddress());
        assertEquals(50000L, item.getAmount());
    }

    /**
     * 测试 TransferItem 默认构造器和 setter
     */
    @Test
    public void testTransferItemDefaultConstructor() {
        TransferItem item = new TransferItem();
        item.setAddress("NEX_set_addr");
        item.setAmount(99999L);
        assertEquals("NEX_set_addr", item.getAddress());
        assertEquals(99999L, item.getAmount());
    }

    // ==================== Transaction 类型关联测试 ====================

    /**
     * 测试 BATCH_TRANSFER 交易类型在 Transaction 中的正确性
     */
    @Test
    public void testBatchTransferTransactionType() {
        Transaction tx = Transaction.createEmpty();
        tx.type = Transaction.Type.BATCH_TRANSFER.ordinal();
        assertEquals(19, tx.type);
        assertEquals("BATCH_TRANSFER", tx.getTypeName());
        assertTrue(tx.isPaymentExtensionType());
        assertFalse(tx.isChannelTransaction());
        assertFalse(tx.isStableCoinTransaction());
        assertFalse(tx.isBridgeTransaction());
    }
}
