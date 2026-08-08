package org.nexus.core.event;

import org.junit.jupiter.api.Test;
import org.nexus.core.Block;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 区块相关事件单元测试。
 *
 * <p>使用 {@code new Block()} 无参构造器（Spring @Component 默认构造）。</p>
 */
class BlockEventTest {

    private Block newBlock() {
        return new Block();
    }

    @Test
    void newBlockEventHoldsBlock() {
        Block b = newBlock();
        NewBlockEvent e = new NewBlockEvent(this, b);
        assertSame(b, e.getBlock());
        assertSame(this, e.getSource());
    }

    @Test
    void newBlockEventSetBlock() {
        NewBlockEvent e = new NewBlockEvent(this, null);
        Block b = newBlock();
        e.setBlock(b);
        assertSame(b, e.getBlock());
    }

    @Test
    void newBestBlockEventHoldsBlock() {
        Block b = newBlock();
        NewBestBlockEvent e = new NewBestBlockEvent(this, b);
        assertSame(b, e.getBlock());
        assertSame(this, e.getSource());
    }

    @Test
    void newBestBlockEventSetBlock() {
        NewBestBlockEvent e = new NewBestBlockEvent(this, null);
        Block b = newBlock();
        e.setBlock(b);
        assertSame(b, e.getBlock());
    }

    @Test
    void newBlockMinedEventHoldsBlock() {
        Block b = newBlock();
        NewBlockMinedEvent e = new NewBlockMinedEvent(this, b);
        assertSame(b, e.getBlock());
        assertSame(this, e.getSource());
    }

    @Test
    void newBlockMinedEventSetBlock() {
        NewBlockMinedEvent e = new NewBlockMinedEvent(this, null);
        Block b = newBlock();
        e.setBlock(b);
        assertSame(b, e.getBlock());
    }

    @Test
    void newConfirmedBlockEventHoldsBlock() {
        Block b = newBlock();
        NewConfirmedBlockEvent e = new NewConfirmedBlockEvent(this, b);
        assertSame(b, e.getBlock());
        assertSame(this, e.getSource());
    }

    @Test
    void newConfirmedBlockEventSetBlock() {
        NewConfirmedBlockEvent e = new NewConfirmedBlockEvent(this, null);
        Block b = newBlock();
        e.setBlock(b);
        assertSame(b, e.getBlock());
    }

    @Test
    void rebootEventHoldsBestBlock() {
        Block b = newBlock();
        RebootEvent e = new RebootEvent(this, b);
        assertSame(b, e.getBestBlock());
        assertSame(this, e.getSource());
    }

    @Test
    void accountUpdatedEventHoldsBlock() {
        Block b = newBlock();
        AccountUpdatedEvent e = new AccountUpdatedEvent(this, b);
        assertSame(b, e.getBlock());
        assertSame(this, e.getSource());
    }

    @Test
    void accountUpdatedEventSetBlock() {
        AccountUpdatedEvent e = new AccountUpdatedEvent(this, null);
        Block b = newBlock();
        e.setBlock(b);
        assertSame(b, e.getBlock());
    }

    @Test
    void accountUpdateFailedEventHoldsBlockAndReason() {
        Block b = newBlock();
        AccountUpdateFailedEvent e = new AccountUpdateFailedEvent(this, b, "db error");
        assertSame(b, e.getBlock());
        assertEquals("db error", e.getReason());
        assertSame(this, e.getSource());
    }

    @Test
    void accountUpdateFailedEventNullReason() {
        Block b = newBlock();
        AccountUpdateFailedEvent e = new AccountUpdateFailedEvent(this, b, null);
        assertSame(b, e.getBlock());
        assertNull(e.getReason());
    }
}