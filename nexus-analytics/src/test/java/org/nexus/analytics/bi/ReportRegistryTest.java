package org.nexus.analytics.bi;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ReportRegistry} 单元测试。
 *
 * <p>覆盖 save/get/list 与 null 边界。
 */
class ReportRegistryTest {

    private ReportRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ReportRegistry();
    }

    @Test
    void save_andGet_shouldRoundTrip() {
        StatisticsReport report = StatisticsReport.builder()
                .reportId("RPT-1").reportType("DAILY").build();
        registry.save(report);

        assertEquals(report, registry.get("RPT-1"));
    }

    @Test
    void save_null_shouldBeNoOp() {
        registry.save(null);

        assertTrue(registry.list().isEmpty());
    }

    @Test
    void save_nullReportId_shouldBeNoOp() {
        registry.save(StatisticsReport.builder().reportType("X").build());

        assertTrue(registry.list().isEmpty());
    }

    @Test
    void get_null_shouldReturnNull() {
        assertNull(registry.get(null));
    }

    @Test
    void get_nonExistent_shouldReturnNull() {
        assertNull(registry.get("NO-SUCH"));
    }

    @Test
    void list_shouldReturnAllReports() {
        registry.save(StatisticsReport.builder().reportId("R1").build());
        registry.save(StatisticsReport.builder().reportId("R2").build());

        List<StatisticsReport> all = registry.list();
        assertEquals(2, all.size());
    }

    @Test
    void list_emptyInitially_shouldReturnEmptyList() {
        assertTrue(registry.list().isEmpty());
    }

    @Test
    void save_sameId_shouldOverwrite() {
        registry.save(StatisticsReport.builder().reportId("R1").reportType("OLD").build());
        registry.save(StatisticsReport.builder().reportId("R1").reportType("NEW").build());

        assertEquals("NEW", registry.get("R1").getReportType());
        assertEquals(1, registry.list().size());
    }
}