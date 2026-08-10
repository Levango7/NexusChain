package org.nexus.gateway.apiversion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link CursorPagination} 单元测试（P4-T7）。
 */
@DisplayName("CursorPagination 游标分页")
class CursorPaginationTest {

    @Nested
    @DisplayName("parseRequest")
    class ParseRequest {

        @Test
        @DisplayName("null cursor + null pageSize → 默认首页 + 默认 pageSize")
        void nullCursorNullSize_defaults() {
            CursorPageRequest req = CursorPagination.parseRequest(null, null);
            assertTrue(req.isFirstPage());
            assertNull(req.getDecodedCursor());
            assertEquals(CursorPagination.DEFAULT_PAGE_SIZE, req.getPageSize());
        }

        @Test
        @DisplayName("空 cursor → 首页")
        void emptyCursor_isFirstPage() {
            CursorPageRequest req = CursorPagination.parseRequest("", 10);
            assertTrue(req.isFirstPage());
            assertEquals(10, req.getPageSize());
        }

        @Test
        @DisplayName("pageSize < 1 → 规范化为 1")
        void pageSizeBelowMin_clampedToMin() {
            CursorPageRequest req = CursorPagination.parseRequest(null, 0);
            assertEquals(1, req.getPageSize());
        }

        @Test
        @DisplayName("pageSize > 100 → 规范化为 100")
        void pageSizeAboveMax_clampedToMax() {
            CursorPageRequest req = CursorPagination.parseRequest(null, 500);
            assertEquals(CursorPagination.MAX_PAGE_SIZE, req.getPageSize());
        }

        @Test
        @DisplayName("有效 cursor → 解码为排序键")
        void validCursor_decoded() {
            String cursor = CursorPagination.parseRequest(null, 20).getRawCursor();
            // 用 encodeCursor 生成一个有效游标
            String encoded = encodeCursor("123");
            CursorPageRequest req = CursorPagination.parseRequest(encoded, 20);
            assertFalse(req.isFirstPage());
            assertEquals("123", req.getDecodedCursor());
        }

        @Test
        @DisplayName("无效 cursor（非 base64）→ 抛 IllegalArgumentException")
        void invalidCursor_throws() {
            assertThrows(IllegalArgumentException.class,
                    () -> CursorPagination.parseRequest("!!!invalid!!!", 20));
        }
    }

    @Nested
    @DisplayName("buildPage")
    class BuildPage {

        @Test
        @DisplayName("数据量 < pageSize → hasMore=false, nextCursor=null")
        void lessThanPageSize_noMore() {
            CursorPageRequest req = CursorPagination.parseRequest(null, 10);
            List<Long> items = Arrays.asList(1L, 2L, 3L);
            CursorPageResponse<Long> page = CursorPagination.buildPage(items, req, Function.identity());

            assertFalse(page.isHasMore());
            assertNull(page.getNextCursor());
            assertEquals(3, page.getCount());
            assertEquals(10, page.getPageSize());
            assertEquals(items, page.getData());
        }

        @Test
        @DisplayName("数据量 = pageSize → hasMore=false（无多查一条）")
        void exactPageSize_noMore() {
            CursorPageRequest req = CursorPagination.parseRequest(null, 3);
            List<Long> items = Arrays.asList(1L, 2L, 3L);
            CursorPageResponse<Long> page = CursorPagination.buildPage(items, req, Function.identity());

            assertFalse(page.isHasMore());
            assertNull(page.getNextCursor());
            assertEquals(3, page.getCount());
        }

        @Test
        @DisplayName("数据量 = pageSize + 1 → hasMore=true, nextCursor 基于 pageSize 项生成")
        void pageSizePlusOne_hasMore() {
            CursorPageRequest req = CursorPagination.parseRequest(null, 3);
            List<Long> items = Arrays.asList(1L, 2L, 3L, 4L);  // 4 = pageSize + 1
            CursorPageResponse<Long> page = CursorPagination.buildPage(items, req, Function.identity());

            assertTrue(page.isHasMore());
            assertNotNull(page.getNextCursor());
            assertEquals(3, page.getCount());
            // nextCursor 应编码第 3 项的 key
            assertEquals("3", decodeCursor(page.getNextCursor()));
        }

        @Test
        @DisplayName("空列表 → hasMore=false, nextCursor=null, count=0")
        void emptyList() {
            CursorPageRequest req = CursorPagination.parseRequest(null, 10);
            List<Long> items = Collections.emptyList();
            CursorPageResponse<Long> page = CursorPagination.buildPage(items, req, Function.identity());

            assertFalse(page.isHasMore());
            assertNull(page.getNextCursor());
            assertEquals(0, page.getCount());
            assertTrue(page.getData().isEmpty());
        }

        @Test
        @DisplayName("多页遍历：3 页，每页 2 条，共 5 条数据")
        void multiPageTraversal() {
            // 模拟全量数据 5 条
            List<Integer> allData = Arrays.asList(1, 2, 3, 4, 5);
            int pageSize = 2;

            // 第 1 页
            CursorPageRequest req1 = CursorPagination.parseRequest(null, pageSize);
            List<Integer> page1Data = allData.subList(0, pageSize + 1);  // 查 3 条
            CursorPageResponse<Integer> page1 = CursorPagination.buildPage(page1Data, req1, Function.identity());

            assertTrue(page1.isHasMore());
            assertEquals(2, page1.getCount());
            assertEquals(Arrays.asList(1, 2), page1.getData());

            // 第 2 页
            CursorPageRequest req2 = CursorPagination.parseRequest(page1.getNextCursor(), pageSize);
            int afterId = Integer.parseInt(req2.getDecodedCursor());
            List<Integer> page2Data = allData.stream()
                    .filter(i -> i > afterId)
                    .limit(pageSize + 1)
                    .toList();
            CursorPageResponse<Integer> page2 = CursorPagination.buildPage(page2Data, req2, Function.identity());

            assertTrue(page2.isHasMore());
            assertEquals(Arrays.asList(3, 4), page2.getData());

            // 第 3 页
            CursorPageRequest req3 = CursorPagination.parseRequest(page2.getNextCursor(), pageSize);
            int afterId2 = Integer.parseInt(req3.getDecodedCursor());
            List<Integer> page3Data = allData.stream()
                    .filter(i -> i > afterId2)
                    .limit(pageSize + 1)
                    .toList();
            CursorPageResponse<Integer> page3 = CursorPagination.buildPage(page3Data, req3, Function.identity());

            assertFalse(page3.isHasMore());
            assertEquals(List.of(5), page3.getData());
        }
    }

    @Nested
    @DisplayName("游标编解码")
    class CursorCodec {

        @Test
        @DisplayName("encode → decode 往返一致")
        void encodeDecodeRoundTrip() {
            String key = "12345";
            String encoded = encodeCursor(key);
            String decoded = decodeCursor(encoded);
            assertEquals(key, decoded);
        }

        @Test
        @DisplayName("encode(null) → null")
        void encodeNull_returnsNull() {
            assertNull(encodeCursor(null));
        }

        @Test
        @DisplayName("decode(空字符串) → null")
        void decodeEmpty_returnsNull() {
            assertNull(decodeCursor(""));
        }

        @Test
        @DisplayName("decode(null) → null")
        void decodeNull_returnsNull() {
            assertNull(decodeCursor(null));
        }

        @Test
        @DisplayName("游标使用 URL 安全 base64（无 +/= 字符）")
        void cursorIsUrlSafeBase64() {
            String encoded = encodeCursor("1234567890");
            assertNotNull(encoded);
            assertFalse(encoded.contains("+"), "cursor should not contain +");
            assertFalse(encoded.contains("="), "cursor should not contain =");
            assertFalse(encoded.contains("/"), "cursor should not contain /");
        }
    }

    // === 测试辅助方法（通过反射访问包级静态方法） ===

    private static String encodeCursor(String key) {
        try {
            var m = CursorPagination.class.getDeclaredMethod("encodeCursor", String.class);
            m.setAccessible(true);
            return (String) m.invoke(null, key);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String decodeCursor(String cursor) {
        try {
            var m = CursorPagination.class.getDeclaredMethod("decodeCursor", String.class);
            m.setAccessible(true);
            return (String) m.invoke(null, cursor);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}