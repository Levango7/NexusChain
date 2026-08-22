package org.nexus.gateway.apiversion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 字段筛选工具（P4-T7 v2 API）。
 *
 * <p>支持 v2 端点的 {@code fields=id,amount,status} 查询参数，只返回指定字段。
 * 当 {@code fields} 缺省时返回完整对象（透传）。</p>
 *
 * <p>实现策略：用 Jackson 二次序列化——先把对象序列化为 ObjectNode，
 * 再删除不在白名单内的字段。这种方式对任意 POJO 都生效，且不污染领域模型。</p>
 *
 * <p>字段白名单由调用方提供（按资源类型不同而不同），未在白名单中的字段名
 * 直接忽略（不报错——避免字段名探测攻击）。</p>
 */
public final class FieldsFilter {

    private static final Logger log = LoggerFactory.getLogger(FieldsFilter.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private FieldsFilter() {
    }

    /**
     * 解析 fields 查询参数为字段集合。
     *
     * @param fieldsParam 查询参数原始值（如 "id,amount,status"）；null 或空时返回 null 表示"全部字段"
     * @return 字段集合（小写、去重、保序）；null 表示未指定 fields（返回完整对象）
     */
    public static Set<String> parse(String fieldsParam) {
        if (fieldsParam == null || fieldsParam.isEmpty()) {
            return null;
        }
        return Arrays.stream(fieldsParam.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toLowerCase)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 应用字段筛选到单个对象。
     *
     * @param <T>           对象类型
     * @param obj           原始对象
     * @param selectedFields 已解析的字段集合；null 表示返回原对象
     * @return 筛选后的 Map（可直接被 Spring MVC 序列化为 JSON）；若 selectedFields 为 null 则返回原对象
     */
    public static <T> Object apply(T obj, Set<String> selectedFields) {
        if (obj == null) return null;
        if (selectedFields == null || selectedFields.isEmpty()) return obj;

        try {
            // 序列化为 JSON 字符串再解析为 Map
            // 返回 Map 而非 ObjectNode，确保 Spring MVC 能正确序列化
            String json = MAPPER.writeValueAsString(obj);
            @SuppressWarnings("unchecked")
            Map<String, Object> map = MAPPER.readValue(json, Map.class);
            // 删除不在白名单内的字段
            map.keySet().removeIf(key -> !selectedFields.contains(key.toLowerCase()));
            return map;
        } catch (RuntimeException | java.io.IOException e) {
            // 字段筛选失败时降级返回原对象（不阻断请求）

            log.warn("Fields filter failed for {}: {}", obj.getClass().getName(), e.getMessage());
            return obj;
        }
    }

    /**
     * 应用字段筛选到列表（逐元素）。
     *
     * @param <T>           对象类型
     * @param list          原始列表
     * @param selectedFields 已解析的字段集合；null 表示返回原列表
     * @return 筛选后的列表
     */
    public static <T> java.util.List<Object> applyToList(java.util.List<T> list,
                                                          Set<String> selectedFields) {
        Objects.requireNonNull(list, "list must not be null");
        if (selectedFields == null || selectedFields.isEmpty()) {
            return Collections.unmodifiableList(new java.util.ArrayList<>(list));
        }
        return list.stream()
                .map(item -> apply(item, selectedFields))
                .collect(Collectors.toList());
    }

    /**
     * 校验请求字段是否全部在白名单内。
     *
     * @param selectedFields 请求字段
     * @param allowedFields  资源允许的字段白名单
     * @return 不在白名单内的字段集合；空集表示全部合法
     */
    public static Set<String> validateFields(Set<String> selectedFields, Set<String> allowedFields) {
        if (selectedFields == null) return Collections.emptySet();
        Set<String> invalid = new LinkedHashSet<>(selectedFields);
        invalid.removeAll(allowedFields);
        return invalid;
    }
}