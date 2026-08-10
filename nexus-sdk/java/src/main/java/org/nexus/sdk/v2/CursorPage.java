package org.nexus.sdk.v2;

import java.util.List;

/**
 * 游标分页结果（v2 SDK）。
 *
 * @param <T> 元素类型
 */
public final class CursorPage<T> {

    private final List<T> data;
    private final String nextCursor;
    private final boolean hasMore;
    private final int count;
    private final int pageSize;

    public CursorPage(List<T> data, String nextCursor, boolean hasMore, int count, int pageSize) {
        this.data = data;
        this.nextCursor = nextCursor;
        this.hasMore = hasMore;
        this.count = count;
        this.pageSize = pageSize;
    }

    public List<T> data() {
        return data;
    }

    public String nextCursor() {
        return nextCursor;
    }

    public boolean hasMore() {
        return hasMore;
    }

    public int count() {
        return count;
    }

    public int pageSize() {
        return pageSize;
    }
}