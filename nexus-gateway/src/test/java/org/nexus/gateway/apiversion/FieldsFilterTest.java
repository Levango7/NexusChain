package org.nexus.gateway.apiversion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link FieldsFilter} 单元测试（P4-T7）。
 */
@DisplayName("FieldsFilter 字段筛选")
class FieldsFilterTest {

    @Nested
    @DisplayName("parse")
    class Parse {

        @Test
        @DisplayName("null → null（表示返回全部字段）")
        void nullParam_returnsNull() {
            assertNull(FieldsFilter.parse(null));
        }

        @Test
        @DisplayName("空字符串 → null")
        void emptyParam_returnsNull() {
            assertNull(FieldsFilter.parse(""));
        }

        @Test
        @DisplayName("单字段 → 集合含该字段（小写）")
        void singleField() {
            Set<String> result = FieldsFilter.parse("ID");
            assertEquals(Set.of("id"), result);
        }

        @Test
        @DisplayName("多字段 → 集合含全部字段（去重 + 小写 + 保序）")
        void multipleFields() {
            Set<String> result = FieldsFilter.parse("id,amount,Status");
            assertEquals(Set.of("id", "amount", "status"), result);
        }

        @Test
        @DisplayName("含空格 → 自动 trim")
        void withSpaces() {
            Set<String> result = FieldsFilter.parse(" id , amount , status ");
            assertEquals(Set.of("id", "amount", "status"), result);
        }

        @Test
        @DisplayName("重复字段 → 去重")
        void duplicateFields() {
            Set<String> result = FieldsFilter.parse("id,id,amount");
            assertEquals(Set.of("id", "amount"), result);
            assertEquals(2, result.size());
        }
    }

    @Nested
    @DisplayName("apply")
    class Apply {

        @Test
        @DisplayName("null 字段集合 → 返回原对象")
        void nullFields_returnsOriginal() {
            TestBean bean = new TestBean(1L, "abc", "ACTIVE");
            Object result = FieldsFilter.apply(bean, null);
            assertSame(bean, result);
        }

        @Test
        @DisplayName("空字段集合 → 返回原对象")
        void emptyFields_returnsOriginal() {
            TestBean bean = new TestBean(1L, "abc", "ACTIVE");
            Object result = FieldsFilter.apply(bean, Set.of());
            assertSame(bean, result);
        }

        @Test
        @DisplayName("筛选 id,status → 只含这两个字段")
        void selectFields() {
            TestBean bean = new TestBean(1L, "abc", "ACTIVE");
            Object result = FieldsFilter.apply(bean, Set.of("id", "status"));

            assertTrue(result instanceof java.util.Map);
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> map = (java.util.Map<String, Object>) result;
            assertTrue(map.containsKey("id"));
            assertTrue(map.containsKey("status"));
            assertFalse(map.containsKey("name"));
            assertEquals(1, ((Number) map.get("id")).intValue());
            assertEquals("ACTIVE", map.get("status"));
        }

        @Test
        @DisplayName("筛选未知字段 → 该字段被忽略（不报错）")
        void unknownFieldIgnored() {
            TestBean bean = new TestBean(1L, "abc", "ACTIVE");
            Object result = FieldsFilter.apply(bean, Set.of("id", "nonexistent"));
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> map = (java.util.Map<String, Object>) result;
            assertTrue(map.containsKey("id"));
            assertEquals(1, map.size());  // 只有 id
        }

        @Test
        @DisplayName("null 对象 → 返回 null")
        void nullObject_returnsNull() {
            assertNull(FieldsFilter.apply(null, Set.of("id")));
        }
    }

    @Nested
    @DisplayName("applyToList")
    class ApplyToList {

        @Test
        @DisplayName("null 字段集合 → 返回原列表副本")
        void nullFields_returnsOriginalList() {
            List<TestBean> list = List.of(
                    new TestBean(1L, "a", "S1"),
                    new TestBean(2L, "b", "S2"));
            List<Object> result = FieldsFilter.applyToList(list, null);
            assertEquals(list, result);
        }

        @Test
        @DisplayName("筛选字段 → 逐元素筛选")
        void selectFields() {
            List<TestBean> list = List.of(
                    new TestBean(1L, "a", "S1"),
                    new TestBean(2L, "b", "S2"));
            List<Object> result = FieldsFilter.applyToList(list, Set.of("id"));
            assertEquals(2, result.size());
            for (Object item : result) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> map = (java.util.Map<String, Object>) item;
                assertTrue(map.containsKey("id"));
                assertFalse(map.containsKey("name"));
            }
        }
    }

    @Nested
    @DisplayName("validateFields")
    class ValidateFields {

        @Test
        @DisplayName("全部合法 → 返回空集")
        void allValid_returnsEmpty() {
            Set<String> selected = Set.of("id", "amount");
            Set<String> allowed = Set.of("id", "amount", "status");
            assertTrue(FieldsFilter.validateFields(selected, allowed).isEmpty());
        }

        @Test
        @DisplayName("含非法字段 → 返回非法字段集")
        void containsInvalid_returnsInvalid() {
            Set<String> selected = Set.of("id", "xxx", "yyy");
            Set<String> allowed = Set.of("id", "amount", "status");
            Set<String> invalid = FieldsFilter.validateFields(selected, allowed);
            assertEquals(Set.of("xxx", "yyy"), invalid);
        }

        @Test
        @DisplayName("null selected → 返回空集")
        void nullSelected_returnsEmpty() {
            assertTrue(FieldsFilter.validateFields(null, Set.of("id")).isEmpty());
        }
    }

    /** 测试用 POJO */
    static class TestBean {
        private final Long id;
        private final String name;
        private final String status;

        TestBean(Long id, String name, String status) {
            this.id = id;
            this.name = name;
            this.status = status;
        }

        public Long getId() { return id; }
        public String getName() { return name; }
        public String getStatus() { return status; }
    }
}