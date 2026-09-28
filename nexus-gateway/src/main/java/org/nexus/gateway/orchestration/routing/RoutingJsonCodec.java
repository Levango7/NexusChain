package org.nexus.gateway.orchestration.routing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 路由 JSON 列编解码工具 — 供 Wave 16 新实体的 JSON 列（如 objective_weights_json、
 * conditions_json 等）序列化/反序列化使用。
 *
 * <p>遵循项目现有 JSON 序列化模式（参考 {@code RoutingRuleEntity}）：静态 ObjectMapper
 * 实例 + TypeReference 处理泛型 + IllegalArgumentException 包装异常。</p>
 */
public final class RoutingJsonCodec {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private RoutingJsonCodec() {
        // 工具类，禁止实例化
    }

    // ===== Map<String, String> 编解码 =====

    /**
     * 将 Map&lt;String,String&gt; 序列化为 JSON 字符串。
     *
     * @param map 条件映射，可为 null
     * @return JSON 字符串；输入为 null 时返回 null
     * @throws IllegalArgumentException 序列化失败时抛出
     */
    public static String toJson(Map<String, String> map) {
        if (map == null) return null;
        try {
            return OBJECT_MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize map to JSON", e);
        }
    }

    /**
     * 将 JSON 字符串反序列化为 Map&lt;String,String&gt;。
     *
     * @param json JSON 字符串，可为 null/blank
     * @return 解析后的 map；输入为 null/blank 时返回空 map
     * @throws IllegalArgumentException 反序列化失败时抛出
     */
    public static Map<String, String> fromJsonToMap(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to deserialize JSON to map: " + e.getMessage(), e);
        }
    }

    // ===== List<String> 编解码 =====

    /**
     * 将 List&lt;String&gt; 序列化为 JSON 字符串。
     *
     * @param list 字符串列表，可为 null
     * @return JSON 字符串；输入为 null 时返回 null
     * @throws IllegalArgumentException 序列化失败时抛出
     */
    public static String toJson(List<String> list) {
        if (list == null) return null;
        try {
            return OBJECT_MAPPER.writeValueAsString(list);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize list to JSON", e);
        }
    }

    /**
     * 将 JSON 字符串反序列化为 List&lt;String&gt;。
     *
     * @param json JSON 字符串，可为 null/blank
     * @return 解析后的列表；输入为 null/blank 时返回空列表
     * @throws IllegalArgumentException 反序列化失败时抛出
     */
    public static List<String> fromJsonToList(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to deserialize JSON to list: " + e.getMessage(), e);
        }
    }

    // ===== 通用对象编解码 =====

    /**
     * 将任意对象序列化为 JSON 字符串。
     *
     * @param obj 待序列化对象，可为 null
     * @return JSON 字符串；输入为 null 时返回 null
     * @throws IllegalArgumentException 序列化失败时抛出
     */
    public static String writeValueAsString(Object obj) {
        if (obj == null) return null;
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize object to JSON", e);
        }
    }

    /**
     * 将 JSON 字符串反序列化为指定类型的对象。
     *
     * @param json       JSON 字符串
     * @param targetType 目标类型的 TypeReference
     * @return 解析后的对象；输入为 null/blank 时返回 null
     * @throws IllegalArgumentException 反序列化失败时抛出
     */
    public static <T> T readValue(String json, TypeReference<T> targetType) {
        if (json == null || json.isBlank()) return null;
        try {
            return OBJECT_MAPPER.readValue(json, targetType);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to deserialize JSON: " + e.getMessage(), e);
        }
    }
}