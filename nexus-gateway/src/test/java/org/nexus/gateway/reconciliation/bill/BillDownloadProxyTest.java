package org.nexus.gateway.reconciliation.bill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.reconciliation.ChannelRecord;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * BillDownloadProxy 单元测试。
 */
class BillDownloadProxyTest {

    private WeChatBillDownloadClient weChatClient;
    private AlipayBillDownloadClient alipayClient;
    private WeChatBillParser weChatParser;
    private AlipayBillParser alipayParser;
    private BillDownloadProxy proxy;

    private static final Long MERCHANT_ID = 500L;
    private static final String BILL_DATE = "2026-09-27";

    @BeforeEach
    void setUp() {
        weChatClient = mock(WeChatBillDownloadClient.class);
        alipayClient = mock(AlipayBillDownloadClient.class);
        weChatParser = mock(WeChatBillParser.class);
        alipayParser = mock(AlipayBillParser.class);
        proxy = new BillDownloadProxy(weChatClient, alipayClient, weChatParser, alipayParser);
    }

    // ==================== 微信渠道 ====================

    @Test
    @DisplayName("downloadBill — WECHAT 渠道成功下载")
    void downloadWeChatBillSuccess() {
        String csv = "# header\norder_no,amount,status\nORD-001,100.00,SUCCESS\n";
        List<ChannelRecord> records = List.of(
                new ChannelRecord("ORD-001", new BigDecimal("100.00"), "SUCCESS"));

        when(weChatClient.downloadBill(MERCHANT_ID, BILL_DATE)).thenReturn(csv);
        when(weChatClient.isDryRun()).thenReturn(true);
        when(weChatParser.parse(csv)).thenReturn(records);

        BillDownloadResult result = proxy.downloadBill("WECHAT", MERCHANT_ID, BILL_DATE);

        assertTrue(result.isSuccess());
        assertEquals("WECHAT", result.getChannelType());
        assertEquals(MERCHANT_ID, result.getMerchantId());
        assertEquals(BILL_DATE, result.getBillDate());
        assertEquals(1, result.getRecordCount());
        assertTrue(result.isDryRun());
        verify(weChatClient).downloadBill(MERCHANT_ID, BILL_DATE);
        verify(weChatParser).parse(csv);
    }

    @Test
    @DisplayName("downloadBill — WECHAT 渠道空内容返回失败")
    void downloadWeChatBillEmptyContent() {
        when(weChatClient.downloadBill(MERCHANT_ID, BILL_DATE)).thenReturn("");

        BillDownloadResult result = proxy.downloadBill("WECHAT", MERCHANT_ID, BILL_DATE);

        assertFalse(result.isSuccess());
        assertEquals("WECHAT", result.getChannelType());
        assertNotNull(result.getErrorMessage());
    }

    // ==================== 支付宝渠道 ====================

    @Test
    @DisplayName("downloadBill — ALIPAY 渠道成功下载")
    void downloadAlipayBillSuccess() {
        String csv = "# header\n商户订单号,金额,状态\nORD-002,200.00,TRADE_SUCCESS\n";
        List<ChannelRecord> records = List.of(
                new ChannelRecord("ORD-002", new BigDecimal("200.00"), "TRADE_SUCCESS"));

        when(alipayClient.downloadBill(MERCHANT_ID, BILL_DATE)).thenReturn(csv);
        when(alipayClient.isDryRun()).thenReturn(false);
        when(alipayParser.parse(csv)).thenReturn(records);

        BillDownloadResult result = proxy.downloadBill("ALIPAY", MERCHANT_ID, BILL_DATE);

        assertTrue(result.isSuccess());
        assertEquals("ALIPAY", result.getChannelType());
        assertEquals(1, result.getRecordCount());
        assertFalse(result.isDryRun());
        verify(alipayClient).downloadBill(MERCHANT_ID, BILL_DATE);
        verify(alipayParser).parse(csv);
    }

    // ==================== 边界场景 ====================

    @Test
    @DisplayName("downloadBill — 不支持的渠道类型返回失败")
    void downloadBillUnsupportedChannel() {
        BillDownloadResult result = proxy.downloadBill("UNIONPAY", MERCHANT_ID, BILL_DATE);

        assertFalse(result.isSuccess());
        assertEquals("UNIONPAY", result.getChannelType());
        assertTrue(result.getErrorMessage().contains("不支持的渠道类型"));
    }

    @Test
    @DisplayName("downloadBill — null 渠道类型返回失败")
    void downloadBillNullChannel() {
        BillDownloadResult result = proxy.downloadBill(null, MERCHANT_ID, BILL_DATE);

        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
    }

    @Test
    @DisplayName("isDryRun — WECHAT 渠道返回 dry-run 状态")
    void isDryRunWeChat() {
        when(weChatClient.isDryRun()).thenReturn(true);
        assertTrue(proxy.isDryRun("WECHAT"));
    }

    @Test
    @DisplayName("isDryRun — ALIPAY 渠道返回 dry-run 状态")
    void isDryRunAlipay() {
        when(alipayClient.isDryRun()).thenReturn(false);
        assertFalse(proxy.isDryRun("ALIPAY"));
    }

    @Test
    @DisplayName("isDryRun — 未知渠道默认返回 true")
    void isDryRunUnknownChannel() {
        assertTrue(proxy.isDryRun("UNKNOWN"));
        assertTrue(proxy.isDryRun(null));
    }
}