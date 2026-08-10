package org.nexus.command;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Dllparameter} 单元测试。
 */
class DllparameterTest {

    @Test
    void defaultConstructorCreatesEmpty() {
        Dllparameter p = new Dllparameter();
        assertNull(p.getJarname());
        assertNull(p.getClasspackage());
        assertNull(p.getMethodname());
        assertNull(p.getClasstype());
        assertNull(p.getObjectList());
    }

    @Test
    void settersAndGetters() {
        Dllparameter p = new Dllparameter();
        p.setJarname("test.jar");
        p.setClasspackage("org.nexus.test");
        p.setMethodname("run");
        p.setClasstype(Arrays.asList(1, 2, 3));
        p.setObjectList(Arrays.asList("a", "b"));

        assertEquals("test.jar", p.getJarname());
        assertEquals("org.nexus.test", p.getClasspackage());
        assertEquals("run", p.getMethodname());
        assertEquals(3, p.getClasstype().size());
        assertEquals(2, p.getObjectList().size());
    }

    @Test
    void emptyLists() {
        Dllparameter p = new Dllparameter();
        p.setClasstype(Collections.emptyList());
        p.setObjectList(Collections.emptyList());
        assertTrue(p.getClasstype().isEmpty());
        assertTrue(p.getObjectList().isEmpty());
    }
}