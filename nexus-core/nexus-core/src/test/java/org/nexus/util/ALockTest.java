package org.nexus.util;

import org.junit.jupiter.api.Test;

import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ALock} 单元测试。
 */
class ALockTest {

    @Test
    void lockAndCloseReleasesLock() {
        ReentrantLock lock = new ReentrantLock();
        ALock aLock = new ALock(lock);
        ALock returned = aLock.lock();
        assertSame(aLock, returned);
        assertTrue(lock.isLocked());
        aLock.close();
        assertFalse(lock.isLocked());
    }

    @Test
    void worksInTryWithResources() {
        ReentrantLock lock = new ReentrantLock();
        try (ALock l = new ALock(lock).lock()) {
            assertTrue(lock.isLocked());
        }
        assertFalse(lock.isLocked());
    }

    @Test
    void multipleLocksAndUnlocks() {
        ReentrantLock lock = new ReentrantLock();
        ALock a1 = new ALock(lock);
        a1.lock();
        assertTrue(lock.isLocked());
        a1.close();
        assertFalse(lock.isLocked());

        ALock a2 = new ALock(lock);
        a2.lock();
        assertTrue(lock.isLocked());
        a2.close();
        assertFalse(lock.isLocked());
    }
}