package org.nexus.signing.util;

import org.junit.Test;
import org.nexus.signing.pool.NonceState;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * {@link BeanToMapUtil} 单元测试。
 *
 * <p>覆盖 {@link BeanToMapUtil#convertMap} 方法将 Map 转换为 JavaBean 的能力。
 * 主要使用场景：NoncePool 从 LevelDB 反序列化 NonceState。</p>
 *
 * <p>注：JavaBeans Introspector 从 getter/setter 推导属性名，
 * {@code getTranHash()}/{@code setTranHash()} → 属性名为 {@code "tranHash"}（小写 t）。</p>
 */
public class BeanToMapUtilTest {

    @Test
    public void testConvertMap_fullNonceState() throws Exception {
        Map<String, Object> map = new HashMap<>();
        map.put("tranHash", "0xabc");
        map.put("nonce", 42L);
        map.put("datetime", 1234567890L);
        map.put("status", "USED");

        NonceState result = (NonceState) BeanToMapUtil.convertMap(NonceState.class, map);
        assertNotNull(result);
        assertEquals("0xabc", result.getTranHash());
        assertEquals(42L, result.getNonce());
        assertEquals(1234567890L, result.getDatetime());
        assertEquals("USED", result.getStatus());
    }

    @Test
    public void testConvertMap_partialMap_missingFieldsKeepDefaults() throws Exception {
        Map<String, Object> map = new HashMap<>();
        map.put("nonce", 10L);

        NonceState result = (NonceState) BeanToMapUtil.convertMap(NonceState.class, map);
        assertNotNull(result);
        assertEquals(10L, result.getNonce());
        // 其他字段保持默认
        assertEquals(NonceState.STATUS_AVAILABLE, result.getStatus());
    }

    @Test
    public void testConvertMap_emptyMap_returnsDefaultInstance() throws Exception {
        Map<String, Object> map = new HashMap<>();
        NonceState result = (NonceState) BeanToMapUtil.convertMap(NonceState.class, map);
        assertNotNull(result);
        assertEquals(NonceState.STATUS_AVAILABLE, result.getStatus());
    }

    @Test
    public void testConvertMap_withIntegerNonce_convertedToLong() throws Exception {
        // LevelDB 反序列化时 nonce 可能以 Integer 形式出现（Gson 默认行为）
        Map<String, Object> map = new HashMap<>();
        map.put("tranHash", "0xdef");
        map.put("nonce", 15);  // Integer
        map.put("datetime", 999);  // Integer

        NonceState result = (NonceState) BeanToMapUtil.convertMap(NonceState.class, map);
        assertNotNull(result);
        assertEquals("0xdef", result.getTranHash());
        // Integer → long 自动转换
        assertEquals(15L, result.getNonce());
    }

    @Test
    public void testConvertMap_statusField() throws Exception {
        Map<String, Object> map = new HashMap<>();
        map.put("status", "LOCKED");

        NonceState result = (NonceState) BeanToMapUtil.convertMap(NonceState.class, map);
        assertNotNull(result);
        assertEquals("LOCKED", result.getStatus());
        assertEquals(true, result.isLocked());
    }
}
