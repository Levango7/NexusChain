package org.nexus.sdk.util;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JsonUtil 单元测试。
 *
 * p.验证 Gson 单例可正常序列化/反序列化。
 */
class JsonUtilTest {

    @Test
    void gson_shouldSerializeMap() {
        Map<String, Object> map = Map.of("key", "value", "num", 42);
        String json = JsonUtil.GSON.toJson(map);

        assertNotNull(json);
        assertTrue(json.contains("key"));
        assertTrue(json.contains("value"));
    }

    @Test
    void gson_shouldDeserializeMap() {
        String json = "{\"key\":\"value\"}";
        @SuppressWarnings("unchecked")
        Map<String, String> map = JsonUtil.GSON.fromJson(json, Map.class);

        assertEquals("value", map.get("key"));
    }

    @Test
    void gson_shouldSerializeNulls() {
        // serializeNulls() 镜像 fastjson 默认行为
        Map<String, Object> map = new java.util.HashMap<>();
        map.put("key", null);
        String json = JsonUtil.GSON.toJson(map);

        assertTrue(json.contains("null"));
    }

    @Test
    void gsonPretty_shouldProducePrettyJson() {
        Map<String, Object> map = Map.of("key", "value");
        String json = JsonUtil.GSON_PRETTY.toJson(map);

        assertNotNull(json);
        // pretty printing 应包含换行
        assertTrue(json.contains("\n"));
    }

    @Test
    void gson_roundTrip_shouldPreserveData() {
        Map<String, Object> original = Map.of("a", 1, "b", "two");
        String json = JsonUtil.GSON.toJson(original);
        @SuppressWarnings("unchecked")
        Map<String, Object> recovered = JsonUtil.GSON.fromJson(json, Map.class);

        assertEquals(1.0, ((Number) recovered.get("a")).doubleValue(), 0.001);
        assertEquals("two", recovered.get("b"));
    }
}