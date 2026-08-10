package org.nexus.gateway.apiversion;

import java.util.Objects;

/**
 * 游标分页请求（v2 API）。
 *
 * <p>由 {@link CursorPagination#parseRequest(String, Integer)} 构造，
 * 不可变。下游 Repository 据此查询数据。</p>
 */
public final class CursorPageRequest {

    /** 客户端原始传入的 base64 游标（首页请求为 null） */
    private final String rawCursor;

    /** 解码后的排序键明文（首页请求为 null） */
    private final String decodedCursor;

    /** 规范化后的每页条数（1-100） */
    private final int pageSize;

    public CursorPageRequest(String rawCursor, String decodedCursor, int pageSize) {
        this.rawCursor = rawCursor;
        this.decodedCursor = decodedCursor;
        this.pageSize = pageSize;
    }

    public String getRawCursor() {
        return rawCursor;
    }

    /** 是否为首页请求（无游标） */
    public boolean isFirstPage() {
        return decodedCursor == null;
    }

    /** 解码后的排序键；首页请求返回 null */
    public String getDecodedCursor() {
        return decodedCursor;
    }

    public int getPageSize() {
        return pageSize;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CursorPageRequest)) return false;
        CursorPageRequest that = (CursorPageRequest) o;
        return pageSize == that.pageSize
                && Objects.equals(rawCursor, that.rawCursor)
                && Objects.equals(decodedCursor, that.decodedCursor);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rawCursor, decodedCursor, pageSize);
    }

    @Override
    public String toString() {
        return "CursorPageRequest{cursor=" + rawCursor
                + ", pageSize=" + pageSize + "}";
    }
}