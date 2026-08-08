package org.nexus.p2p;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Context} 单元测试。
 */
class ContextTest {

    @Test
    void defaultFlagsAreFalse() {
        Context c = new Context();
        assertFalse(c.broken);
        assertFalse(c.remove);
        assertFalse(c.block);
        assertFalse(c.keep);
        assertFalse(c.pending);
        assertFalse(c.relay);
        assertNull(c.response);
        assertNull(c.getPayload());
    }

    @Test
    void exitSetsBroken() {
        Context c = new Context();
        c.exit();
        assertTrue(c.broken);
    }

    @Test
    void removeSetsRemove() {
        Context c = new Context();
        c.remove();
        assertTrue(c.remove);
    }

    @Test
    void blockSetsBlock() {
        Context c = new Context();
        c.block();
        assertTrue(c.block);
    }

    @Test
    void keepSetsKeep() {
        Context c = new Context();
        c.keep();
        assertTrue(c.keep);
    }

    @Test
    void pendSetsPending() {
        Context c = new Context();
        c.pend();
        assertTrue(c.pending);
    }

    @Test
    void relaySetsRelay() {
        Context c = new Context();
        c.relay();
        assertTrue(c.relay);
    }

    @Test
    void responseStoresValue() {
        Context c = new Context();
        com.google.protobuf.AbstractMessage msg = null;
        c.response(msg);
        assertNull(c.response);
    }
}