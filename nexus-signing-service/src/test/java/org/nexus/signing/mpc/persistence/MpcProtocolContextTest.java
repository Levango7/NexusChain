package org.nexus.signing.mpc.persistence;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@link MpcProtocolContext} 单元测试。
 */
public class MpcProtocolContextTest {

    @Test
    public void testGettersAndSetters() {
        MpcProtocolContext ctx = new MpcProtocolContext();
        ctx.setId(42L);
        ctx.setSessionId("s1");
        ctx.setRound(3);
        ctx.setParticipantId("p1");
        Map<String, String> state = new HashMap<>();
        state.put("k", "v");
        ctx.setState(state);
        LocalDateTime created = LocalDateTime.now();
        ctx.setCreatedAt(created);

        assertEquals(Long.valueOf(42L), ctx.getId());
        assertEquals(ctx.getSessionId(), "s1");
        assertEquals(3, ctx.getRound());
        assertEquals(ctx.getParticipantId(), "p1");
        assertEquals(ctx.getState().get("k"), "v");
        assertEquals(created, ctx.getCreatedAt());
    }

    @Test
    public void testDefaultCreatedAtNotNull() {
        MpcProtocolContext ctx = new MpcProtocolContext();
        assertNotNull(ctx.getCreatedAt());
    }
}