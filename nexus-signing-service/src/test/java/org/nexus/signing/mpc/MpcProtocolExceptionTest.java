package org.nexus.signing.mpc;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * {@link MpcProtocolException} 单元测试。
 */
public class MpcProtocolExceptionTest {

    @Test
    public void testTwoArgConstructor() {
        MpcProtocolException ex = new MpcProtocolException(
                MpcProtocolException.Reason.TIMEOUT, "timed out");
        assertEquals(MpcProtocolException.Reason.TIMEOUT, ex.getReason());
        assertEquals("timed out", ex.getMessage());
        assertNull(ex.getBlamedParticipant());
    }

    @Test
    public void testThreeArgConstructorWithBlamedParticipant() {
        MpcProtocolException ex = new MpcProtocolException(
                MpcProtocolException.Reason.MALICIOUS_PARTICIPANT, "bad proof", "p3");
        assertEquals(MpcProtocolException.Reason.MALICIOUS_PARTICIPANT, ex.getReason());
        assertEquals("bad proof", ex.getMessage());
        assertEquals("p3", ex.getBlamedParticipant());
    }

    @Test
    public void testCauseConstructor() {
        Throwable cause = new RuntimeException("root");
        MpcProtocolException ex = new MpcProtocolException(
                MpcProtocolException.Reason.SHARE_VERIFICATION_FAILED, "verify failed", cause);
        assertEquals(MpcProtocolException.Reason.SHARE_VERIFICATION_FAILED, ex.getReason());
        assertEquals("verify failed", ex.getMessage());
        assertEquals(cause, ex.getCause());
        assertNull(ex.getBlamedParticipant());
    }

    @Test
    public void testAllReasonValuesExist() {
        // 确保所有 reason 枚举值可被使用（覆盖枚举）
        for (MpcProtocolException.Reason r : MpcProtocolException.Reason.values()) {
            MpcProtocolException ex = new MpcProtocolException(r, r.name());
            assertEquals(r, ex.getReason());
        }
    }
}