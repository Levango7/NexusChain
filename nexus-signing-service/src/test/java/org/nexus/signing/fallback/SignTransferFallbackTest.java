package org.nexus.signing.fallback;

import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowException;
import org.junit.Test;

import java.math.BigDecimal;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

/**
 * {@link SignTransferFallback} 单元测试。
 *
 * <p>覆盖所有静态 fallback 方法：
 * <ul>
 *   <li>signTransferFallback — 限流/熔断降级返回 null</li>
 *   <li>transferFallback — legacy 端点降级返回 null</li>
 *   <li>canSignViaMpcFallback — fail-closed 返回 false</li>
 *   <li>getNoncePoolFallback — 降级返回 null</li>
 * </ul></p>
 */
public class SignTransferFallbackTest {

    @Test
    public void testSignTransferFallback_flowException_returnsNull() {
        String result = SignTransferFallback.signTransferFallback(
                "fromPub", "toPubHash", new BigDecimal("100"), new FlowException("flow"));
        assertNull(result);
    }

    @Test
    public void testSignTransferFallback_degradeException_returnsNull() {
        String result = SignTransferFallback.signTransferFallback(
                "fromPub", "toPubHash", new BigDecimal("100"), new DegradeException("degrade"));
        assertNull(result);
    }

    @Test
    public void testSignTransferFallback_unknownException_returnsNull() {
        // 使用自定义 BlockException 子类模拟未知异常
        BlockException unknown = new BlockException("unknown") {};
        String result = SignTransferFallback.signTransferFallback(
                "fromPub", "toPubHash", BigDecimal.ZERO, unknown);
        assertNull(result);
    }

    @Test
    public void testTransferFallback_flowException_returnsNull() {
        String result = SignTransferFallback.transferFallback(
                "fromPub", "toPubHash", new BigDecimal("100"), "privateKey", new FlowException("flow"));
        assertNull(result);
    }

    @Test
    public void testTransferFallback_degradeException_returnsNull() {
        String result = SignTransferFallback.transferFallback(
                "fromPub", "toPubHash", new BigDecimal("100"), "privateKey", new DegradeException("degrade"));
        assertNull(result);
    }

    @Test
    public void testTransferFallback_unknownException_returnsNull() {
        BlockException unknown = new BlockException("unknown") {};
        String result = SignTransferFallback.transferFallback(
                "fromPub", "toPubHash", BigDecimal.ZERO, "pk", unknown);
        assertNull(result);
    }

    @Test
    public void testCanSignViaMpcFallback_flowException_returnsFalse() {
        boolean result = SignTransferFallback.canSignViaMpcFallback(
                new BigDecimal("100"), new FlowException("flow"));
        assertFalse(result);
    }

    @Test
    public void testCanSignViaMpcFallback_degradeException_returnsFalse() {
        boolean result = SignTransferFallback.canSignViaMpcFallback(
                new BigDecimal("100"), new DegradeException("degrade"));
        assertFalse(result);
    }

    @Test
    public void testCanSignViaMpcFallback_unknownException_returnsFalse() {
        BlockException unknown = new BlockException("unknown") {};
        boolean result = SignTransferFallback.canSignViaMpcFallback(BigDecimal.ZERO, unknown);
        assertFalse(result);
    }

    @Test
    public void testGetNoncePoolFallback_flowException_returnsNull() {
        Object result = SignTransferFallback.getNoncePoolFallback("addr1", new FlowException("flow"));
        assertNull(result);
    }

    @Test
    public void testGetNoncePoolFallback_degradeException_returnsNull() {
        Object result = SignTransferFallback.getNoncePoolFallback("addr1", new DegradeException("degrade"));
        assertNull(result);
    }

    @Test
    public void testGetNoncePoolFallback_unknownException_returnsNull() {
        BlockException unknown = new BlockException("unknown") {};
        Object result = SignTransferFallback.getNoncePoolFallback("addr1", unknown);
        assertNull(result);
    }

    @Test
    public void testSignTransferFallback_nullAmount_returnsNull() {
        String result = SignTransferFallback.signTransferFallback(
                "from", "to", null, new FlowException("flow"));
        assertNull(result);
    }

    @Test
    public void testGetNoncePoolFallback_nullAddress_returnsNull() {
        Object result = SignTransferFallback.getNoncePoolFallback(null, new FlowException("flow"));
        assertNull(result);
    }
}