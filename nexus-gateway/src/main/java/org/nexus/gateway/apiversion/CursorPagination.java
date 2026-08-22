package org.nexus.gateway.apiversion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * 游标分页工具（P4-T7 v2 API）。
 *
 * <p>替代 v1 的 offset/limit 分页，采用 cursor + pageSize 模式：</p>
 * <ul>
 *   <li><b>cursor</b>：base64 编码的 opaque 字符串，指向上一页最后一项的排序键</li>
 *   <li><b>pageSize</b>：每页条数（默认 20，最大 100）</li>
 *   <li><b>nextCursor</b>：响应中返回下一页游标，{@code null} 表示已到末尾</li>
 * </ul>
 *
 * <p>游标内部结构为 JSON {@code {"k":"<sortKey>","v":<version>}}，base64(UTF-8) 编码后传输。
 * 这种方式使游标对客户端完全 opaque，服务端可自由扩展内部结构（如加入过滤快照、排序方向等）。</p>
 *
 * <p>典型用法：</p>
 * <pre>{@code
 * CursorPageRequest req = CursorPagination.parseRequest(cursorParam, pageSizeParam);
 * List<PaymentOrder> all = repository.findByMerchantIdAfter(mid, req.getDecodedCursor(), PageRequest.ofSize(req.getPageSize() + 1));
 * CursorPageResponse<PaymentOrder> resp = CursorPagination.buildPage(all, req, PaymentOrder::getId);
 * return ResponseEntity.ok(resp);
 * }</pre>
 */
public final class CursorPagination {

    /** 默认每页条数 */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** 最大每页条数 */
    public static final int MAX_PAGE_SIZE = 100;

    /** 最小每页条数 */
    public static final int MIN_PAGE_SIZE = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CursorPagination() {
    }

    /**
     * 解析分页请求参数。
     *
     * @param cursor  客户端传入的游标（首页请求传 null 或空字符串）
     * @param pageSize 客户端传入的每页条数（null 时取默认值）
     * @return 规范化后的分页请求
     */
    public static CursorPageRequest parseRequest(String cursor, Integer pageSize) {
        int size = normalizePageSize(pageSize);
        String decoded = decodeCursor(cursor);
        return new CursorPageRequest(cursor, decoded, size);
    }

    /**
     * 构建分页响应。
     *
     * <p>调用方需按 {@code pageSize + 1} 查询数据，本方法据此判断是否有下一页：
     * 若返回数据条数 > pageSize，则截取前 pageSize 条并基于第 pageSize 条生成 nextCursor；
     * 否则 nextCursor 为 null，表示已到末尾。</p>
     *
     * @param <T>       数据元素类型
     * @param <K>       排序键类型（需可序列化为字符串）
     * @param items     查询结果（长度应为 pageSize + 1，由调用方控制）
     * @param request   原始分页请求
     * @param keyExtractor 排序键提取器（如 {@code PaymentOrder::getId}）
     * @return 分页响应
     */
    public static <T, K> CursorPageResponse<T> buildPage(List<T> items,
                                                          CursorPageRequest request,
                                                          java.util.function.Function<T, K> keyExtractor) {
        Objects.requireNonNull(items, "items must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(keyExtractor, "keyExtractor must not be null");

        boolean hasNext = items.size() > request.getPageSize();
        List<T> pageItems = hasNext
                ? items.subList(0, request.getPageSize())
                : items;

        String nextCursor = null;
        if (hasNext && !pageItems.isEmpty()) {
            T last = pageItems.get(pageItems.size() - 1);
            K key = keyExtractor.apply(last);
            nextCursor = encodeCursor(String.valueOf(key));
        }

        return new CursorPageResponse<>(pageItems, nextCursor,
                pageItems.size(), request.getPageSize(), hasNext);
    }

    /**
     * 规范化每页条数。
     */
    static int normalizePageSize(Integer pageSize) {
        if (pageSize == null) return DEFAULT_PAGE_SIZE;
        if (pageSize < MIN_PAGE_SIZE) return MIN_PAGE_SIZE;
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    /**
     * 编码游标：plainText → JSON wrapper → base64。
     *
     * <p>包装为 JSON {@code {"k":"..."}} 以便后续扩展内部结构。</p>
     *
     * @param sortKey 排序键明文
     * @return base64 编码的游标
     */
    static String encodeCursor(String sortKey) {
        if (sortKey == null) return null;
        try {
            String json = MAPPER.writeValueAsString(new CursorPayload(sortKey));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to encode cursor", e);
        }
    }

    /**
     * 解码游标：base64 → JSON wrapper → sortKey 明文。
     *
     * @param cursor base64 编码的游标
     * @return 排序键明文；输入为 null/空时返回 null（首页请求）
     */
    static String decodeCursor(String cursor) {
        if (cursor == null || cursor.isEmpty()) return null;
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(cursor);
            String json = new String(bytes, StandardCharsets.UTF_8);
            CursorPayload payload = MAPPER.readValue(json, CursorPayload.class);
            return payload.k;
        } catch (RuntimeException | java.io.IOException e) {
            throw new IllegalArgumentException("Invalid cursor: " + e.getMessage(), e);
        }
    }

    /** 游标内部 JSON 结构 */
    private static final class CursorPayload {
        @com.fasterxml.jackson.annotation.JsonProperty("k")
        String k;

        CursorPayload() {
        }

        CursorPayload(String k) {
            this.k = k;
        }
    }
}