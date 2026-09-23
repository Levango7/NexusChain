package org.nexus.gateway.orchestration.settlement;

import org.junit.jupiter.api.Test;
import org.nexus.gateway.model.SubmissionStatus;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SubmissionStatus 枚举测试 — 验证所有枚举值存在且语义正确。
 */
class SubmissionStatusTest {

    @Test
    void allFiveStatusesExist() {
        SubmissionStatus[] values = SubmissionStatus.values();
        assertEquals(5, values.length, "应有 5 个枚举值");
        assertArrayEquals(
                new SubmissionStatus[]{
                        SubmissionStatus.SIGNING,
                        SubmissionStatus.SUBMITTED,
                        SubmissionStatus.INCLUDED,
                        SubmissionStatus.REORGED,
                        SubmissionStatus.REJECTED
                },
                values
        );
    }

    @Test
    void signingIsFirstStage() {
        assertEquals(0, SubmissionStatus.SIGNING.ordinal(), "SIGNING 应为第一阶段");
    }

    @Test
    void submittedIsSecondStage() {
        assertEquals(1, SubmissionStatus.SUBMITTED.ordinal(), "SUBMITTED 应为第二阶段");
    }

    @Test
    void includedIsThirdStage() {
        assertEquals(2, SubmissionStatus.INCLUDED.ordinal(), "INCLUDED 应为第三阶段");
    }

    @Test
    void reorgedIsFourthStage() {
        assertEquals(3, SubmissionStatus.REORGED.ordinal(), "REORGED 应为第四阶段");
    }

    @Test
    void rejectedIsFifthStage() {
        assertEquals(4, SubmissionStatus.REJECTED.ordinal(), "REJECTED 应为第五阶段");
    }

    @Test
    void valueOfValidNameReturnsEnum() {
        assertEquals(SubmissionStatus.SIGNING, SubmissionStatus.valueOf("SIGNING"));
        assertEquals(SubmissionStatus.SUBMITTED, SubmissionStatus.valueOf("SUBMITTED"));
        assertEquals(SubmissionStatus.INCLUDED, SubmissionStatus.valueOf("INCLUDED"));
        assertEquals(SubmissionStatus.REORGED, SubmissionStatus.valueOf("REORGED"));
        assertEquals(SubmissionStatus.REJECTED, SubmissionStatus.valueOf("REJECTED"));
    }

    @Test
    void valueOfInvalidNameThrows() {
        assertThrows(IllegalArgumentException.class, () -> SubmissionStatus.valueOf("INVALID"));
    }

    @Test
    void valueOfNullThrows() {
        assertThrows(NullPointerException.class, () -> SubmissionStatus.valueOf(null));
    }

    @Test
    void toStringReturnsName() {
        assertEquals("SIGNING", SubmissionStatus.SIGNING.toString());
        assertEquals("SUBMITTED", SubmissionStatus.SUBMITTED.toString());
        assertEquals("INCLUDED", SubmissionStatus.INCLUDED.toString());
        assertEquals("REORGED", SubmissionStatus.REORGED.toString());
        assertEquals("REJECTED", SubmissionStatus.REJECTED.toString());
    }
}