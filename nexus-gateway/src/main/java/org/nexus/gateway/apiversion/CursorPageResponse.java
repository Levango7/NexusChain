package org.nexus.gateway.apiversion;

import java.util.List;
import java.util.Objects;

/**
 * 游标分页响应（v2 API）。
 *
 * <p>JSON 序列化后形如：</p>
 * <pre>{@code
 * {
 *   "data": [ ... ],
 *   "nextCursor": "eyJrIjoiMTIzIn0",
 *   "hasMore": true,
 *   "count": 20,
 *   "pageSize": 20
 * }
 * }</pre>
 *
 * @param <T> 数据元素类型
 */
public final class CursorPageResponse<T> {

    /** 当前页数据 */
    private final List<T> data;

    /** 下一页游标（base64 编码）；已到末尾时为 null */
    private final String nextCursor;

    /** 是否还有更多数据 */
    private final boolean hasMore;

    /** 当前页实际返回条数 */
    private final int count;

    /** 每页条数上限 */
    private final int pageSize;

    public CursorPageResponse(List<T> data, String nextCursor, int count, int pageSize, boolean hasMore) {
        this.data = data;
        this.nextCursor = nextCursor;
        this.count = count;
        this.pageSize = pageSize;
        this.hasMore = hasMore;
    }

    public List<T> getData() {
        return data;
    }

    public String getNextCursor() {
        return nextCursor;
    }

    public boolean isHasMore() {
        return hasMore;
    }

    public int getCount() {
        return count;
    }

    public int getPageSize() {
        return pageSize;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CursorPageResponse)) return false;
        CursorPageResponse<?> that = (CursorPageResponse<?>) o;
        return hasMore == that.hasMore
                && count == that.count
                && pageSize == that.pageSize
                && Objects.equals(data, that.data)
                && Objects.equals(nextCursor, that.nextCursor);
    }

    @Override
    public int hashCode() {
        return Objects.hash(data, nextCursor, hasMore, count, pageSize);
    }
}