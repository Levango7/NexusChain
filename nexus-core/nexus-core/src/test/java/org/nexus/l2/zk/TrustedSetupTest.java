package org.nexus.l2.zk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.l2.zk.TrustedSetup.SetupVersion;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TrustedSetup} 单元测试。
 */
class TrustedSetupTest {

    private TrustedSetup setup;

    @BeforeEach
    void setUp() {
        setup = new TrustedSetup();
    }

    @Test
    void initialActiveVersionIsZero() {
        assertEquals(0, setup.getActiveVersion());
    }

    @Test
    void registerVersionReturnsIncrementingIds() {
        int v1 = setup.registerVersion("c1", "tag1", 3);
        int v2 = setup.registerVersion("c2", "tag2", 5);
        assertEquals(1, v1);
        assertEquals(2, v2);
    }

    @Test
    void firstRegisteredVersionBecomesActive() {
        int v = setup.registerVersion("c1", "tag1", 3);
        assertEquals(v, setup.getActiveVersion());
    }

    @Test
    void setActiveVersionExisting() {
        int v1 = setup.registerVersion("c1", "tag1", 3);
        int v2 = setup.registerVersion("c2", "tag2", 5);
        assertTrue(setup.setActiveVersion(v1));
        assertEquals(v1, setup.getActiveVersion());
        assertTrue(setup.setActiveVersion(v2));
        assertEquals(v2, setup.getActiveVersion());
    }

    @Test
    void setActiveVersionNonExistingReturnsFalse() {
        setup.registerVersion("c1", "tag1", 3);
        assertFalse(setup.setActiveVersion(999));
    }

    @Test
    void getVersionExisting() {
        int v = setup.registerVersion("circuit-1", "tag", 10);
        SetupVersion sv = setup.getVersion(v);
        assertNotNull(sv);
        assertEquals(v, sv.getVersion());
        assertEquals("circuit-1", sv.getCircuitId());
        assertEquals("tag", sv.getCeremonyTag());
        assertEquals(10, sv.getParticipantCount());
        assertNotNull(sv.getRegisteredAt());
    }

    @Test
    void getVersionNonExistingReturnsNull() {
        assertNull(setup.getVersion(999));
    }

    @Test
    void listVersionsReturnsAll() {
        setup.registerVersion("c1", "t1", 3);
        setup.registerVersion("c2", "t2", 5);
        List<SetupVersion> list = setup.listVersions();
        assertEquals(2, list.size());
    }

    @Test
    void listVersionsEmpty() {
        assertTrue(setup.listVersions().isEmpty());
    }

    @Test
    void getActiveVersionInfo() {
        int v = setup.registerVersion("c1", "t1", 3);
        SetupVersion sv = setup.getActiveVersionInfo();
        assertNotNull(sv);
        assertEquals(v, sv.getVersion());
    }

    @Test
    void getActiveVersionInfoNullWhenNoVersions() {
        assertNull(setup.getActiveVersionInfo());
    }

    @Test
    void setupVersionToString() {
        int v = setup.registerVersion("c1", "t1", 3);
        SetupVersion sv = setup.getVersion(v);
        String s = sv.toString();
        assertTrue(s.contains("SetupVersion"));
        assertTrue(s.contains("c1"));
        assertTrue(s.contains("t1"));
    }
}