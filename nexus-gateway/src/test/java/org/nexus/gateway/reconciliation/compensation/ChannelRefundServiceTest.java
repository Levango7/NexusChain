package org.nexus.gateway.reconciliation.compensation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * 渠道原路退回服务门禁测试（长款渠道退款 3/3，P0 修复 2026-10-11）。
 *
 * <p>只覆盖**不发起网络请求**的分支：dry-run、渠道缺失、交易号缺失、
 * 支付宝未接入。真实微信 V3 请求路径由「缺 channelType → 抛错」与上层
 * AutoCompensationRealExecTest 的 mock 覆盖，不在单测里发真实请求。</p>
 */
class ChannelRefundServiceTest {

    private ChannelRefundService newService(boolean sandbox) throws Exception {
        ChannelRefundService svc = new ChannelRefundService(mock(RestTemplate.class));
        set(svc, "sandbox", sandbox);
        set(svc, "merchantPrivateKey", sandbox ? "" : "dummy");
        return svc;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    @DisplayName("dry-run：不发起真实请求，返回模拟退款单号（可联调）")
    void dryRun_returnsSimulatedRefund() throws Exception {
        ChannelRefundService svc = newService(true);
        String ref = svc.refundByChannelTransaction("WECHAT", "WX_TX_1",
                new BigDecimal("100.00"), new BigDecimal("100.00"), "AUTO_REFUND_1");
        assertEquals("wechat_dryrun_refund_AUTO_REFUND_1", ref);
    }

    @Test
    @DisplayName("fail-closed：渠道归属缺失 → 抛错（不可猜渠道退款）")
    void missingChannelType_failsClosed() throws Exception {
        ChannelRefundService svc = newService(false);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                svc.refundByChannelTransaction(null, "WX_TX_1",
                        new BigDecimal("100.00"), new BigDecimal("100.00"), "R1"));
        assertTrue(ex.getMessage().contains("缺渠道归属"), ex.getMessage());
    }

    @Test
    @DisplayName("fail-closed：渠道交易号缺失 → 抛错（无可退对象）")
    void missingTxId_failsClosed() throws Exception {
        ChannelRefundService svc = newService(false);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                svc.refundByChannelTransaction("WECHAT", "  ",
                        new BigDecimal("100.00"), new BigDecimal("100.00"), "R1"));
        assertTrue(ex.getMessage().contains("渠道交易号"), ex.getMessage());
    }

    @Test
    @DisplayName("fail-closed：支付宝原路退回尚未接入 → 明确报错（不猜）")
    void alipayNotWired_failsClosed() throws Exception {
        ChannelRefundService svc = newService(false);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                svc.refundByChannelTransaction("ALIPAY", "ALI_TX_1",
                        new BigDecimal("100.00"), new BigDecimal("100.00"), "R1"));
        assertTrue(ex.getMessage().contains("尚未接入"), ex.getMessage());
    }

    @Test
    @DisplayName("fail-closed：未知渠道类型 → 抛错")
    void unknownChannel_failsClosed() throws Exception {
        ChannelRefundService svc = newService(false);
        assertThrows(IllegalStateException.class, () ->
                svc.refundByChannelTransaction("STRIPE", "S1",
                        new BigDecimal("100.00"), new BigDecimal("100.00"), "R1"));
    }
}
