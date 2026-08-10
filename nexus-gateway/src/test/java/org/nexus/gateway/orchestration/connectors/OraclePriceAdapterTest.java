package org.nexus.gateway.orchestration.connectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.oracle.price.PriceEntry;
import org.nexus.oracle.price.PriceOracle;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link OraclePriceAdapter} 单元测试：覆盖 oracle 缺失、入参非法、
 * 价格无效、正常换算与异常分支。
 */
class OraclePriceAdapterTest {

    @Test
    @DisplayName("isAvailable: oracle 未注入返回 false")
    void isAvailable_noOracle() {
        OraclePriceAdapter adapter = new OraclePriceAdapter(null);
        assertFalse(adapter.isAvailable());
    }

    @Test
    @DisplayName("isAvailable: oracle 已注入返回 true")
    void isAvailable_withOracle() {
        OraclePriceAdapter adapter = new OraclePriceAdapter(mock(PriceOracle.class));
        assertTrue(adapter.isAvailable());
    }

    @Test
    @DisplayName("convertToChainAmount: oracle 为 null 返回 null")
    void convert_oracleNull() {
        OraclePriceAdapter adapter = new OraclePriceAdapter(null);
        assertNull(adapter.convertToChainAmount(BigDecimal.TEN, "NEX"));
    }

    @Test
    @DisplayName("convertToChainAmount: fiatAmount 为 null 返回 null")
    void convert_nullFiat() {
        OraclePriceAdapter adapter = new OraclePriceAdapter(mock(PriceOracle.class));
        assertNull(adapter.convertToChainAmount(null, "NEX"));
    }

    @Test
    @DisplayName("convertToChainAmount: fiatAmount <= 0 返回 null")
    void convert_nonPositiveFiat() {
        OraclePriceAdapter adapter = new OraclePriceAdapter(mock(PriceOracle.class));
        assertNull(adapter.convertToChainAmount(BigDecimal.ZERO, "NEX"));
        assertNull(adapter.convertToChainAmount(new BigDecimal("-1"), "NEX"));
    }

    @Test
    @DisplayName("convertToChainAmount: chainAsset 为 null/空白 返回 null")
    void convert_blankAsset() {
        OraclePriceAdapter adapter = new OraclePriceAdapter(mock(PriceOracle.class));
        assertNull(adapter.convertToChainAmount(BigDecimal.TEN, null));
        assertNull(adapter.convertToChainAmount(BigDecimal.TEN, "  "));
    }

    @Test
    @DisplayName("convertToChainAmount: getPrice 返回 null -> null")
    void convert_noPriceEntry() {
        PriceOracle oracle = mock(PriceOracle.class);
        when(oracle.getPrice("NEX")).thenReturn(null);
        OraclePriceAdapter adapter = new OraclePriceAdapter(oracle);
        assertNull(adapter.convertToChainAmount(BigDecimal.TEN, "NEX"));
    }

    @Test
    @DisplayName("convertToChainAmount: price <= 0 -> null")
    void convert_invalidPrice() {
        PriceOracle oracle = mock(PriceOracle.class);
        PriceEntry entry = mock(PriceEntry.class);
        when(entry.getPrice()).thenReturn(BigDecimal.ZERO);
        when(oracle.getPrice("NEX")).thenReturn(entry);
        OraclePriceAdapter adapter = new OraclePriceAdapter(oracle);
        assertNull(adapter.convertToChainAmount(BigDecimal.TEN, "NEX"));
    }

    @Test
    @DisplayName("convertToChainAmount: 正常换算 fiat/price")
    void convert_normal() {
        PriceOracle oracle = mock(PriceOracle.class);
        PriceEntry entry = mock(PriceEntry.class);
        when(entry.getPrice()).thenReturn(new BigDecimal("2.5"));
        when(oracle.getPrice("NEX")).thenReturn(entry);
        OraclePriceAdapter adapter = new OraclePriceAdapter(oracle);

        // 100 / 2.5 = 40
        BigDecimal result = adapter.convertToChainAmount(new BigDecimal("100"), "NEX");
        assertNotNull(result);
        assertEquals(0, new BigDecimal("40.00000000").compareTo(result));
    }

    @Test
    @DisplayName("convertToChainAmount: oracle 抛异常 -> null")
    void convert_oracleException() {
        PriceOracle oracle = mock(PriceOracle.class);
        when(oracle.getPrice("NEX")).thenThrow(new RuntimeException("oracle down"));
        OraclePriceAdapter adapter = new OraclePriceAdapter(oracle);
        assertNull(adapter.convertToChainAmount(BigDecimal.TEN, "NEX"));
    }
}