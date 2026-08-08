package org.nexus.genesis;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Genesis} 及其内部配置类单元测试。
 */
class GenesisTest {

    @Test
    void defaultConstructorCreatesEmpty() {
        Genesis g = new Genesis();
        assertNull(g.config);
        assertNull(g.coinbase);
        assertNull(g.alloc);
        assertEquals(0, g.timestamp);
    }

    @Test
    void fieldsAreMutable() {
        Genesis g = new Genesis();
        g.coinbase = "cb";
        g.nBits = "1d00ffff";
        g.nonce = "abc";
        g.extraData = "extra";
        g.hashBlock = "hash";
        g.parentHash = "parent";
        g.timestamp = 12345L;

        assertEquals("cb", g.coinbase);
        assertEquals("1d00ffff", g.nBits);
        assertEquals("abc", g.nonce);
        assertEquals("extra", g.extraData);
        assertEquals("hash", g.hashBlock);
        assertEquals("parent", g.parentHash);
        assertEquals(12345L, g.timestamp);
    }

    @Test
    void configFieldsAreMutable() {
        Genesis.Config c = new Genesis.Config();
        c.chainID = 1;
        c.block = 100;
        assertEquals(1, c.chainID);
        assertEquals(100, c.block);
    }

    @Test
    void initAmountFields() {
        Genesis.InitAmount a = new Genesis.InitAmount();
        a.address = "addr1";
        a.balance = new BigDecimal("1000");
        assertEquals("addr1", a.address);
        assertEquals(new BigDecimal("1000"), a.balance);
    }

    @Test
    void incubateAmountFields() {
        Genesis.IncubateAmount a = new Genesis.IncubateAmount();
        a.address = "addr";
        a.balance = 500;
        assertEquals("addr", a.address);
        assertEquals(500, a.balance);
    }

    @Test
    void userIncubateAmountFields() {
        Genesis.UserIncubateAmount a = new Genesis.UserIncubateAmount();
        a.address = "to";
        a.balance = new BigDecimal("100");
        a.days = 30;
        a.remainDays = 20;
        a.shareAddress = "share";
        a.interest = new BigDecimal("0.5");
        a.share = new BigDecimal("0.1");

        assertEquals("to", a.address);
        assertEquals(new BigDecimal("100"), a.balance);
        assertEquals(30, a.days);
        assertEquals(20, a.remainDays);
        assertEquals("share", a.shareAddress);
        assertEquals(new BigDecimal("0.5"), a.interest);
        assertEquals(new BigDecimal("0.1"), a.share);
    }

    @Test
    void allocHoldsLists() {
        Genesis.Alloc alloc = new Genesis.Alloc();
        alloc.initAmount = Collections.emptyList();
        alloc.incubateAmount = Collections.emptyList();
        alloc.userIncubateAmount = Collections.emptyList();
        assertNotNull(alloc.initAmount);
        assertNotNull(alloc.incubateAmount);
        assertNotNull(alloc.userIncubateAmount);
    }

    @Test
    void genesisWithConfigAndAlloc() {
        Genesis g = new Genesis();
        g.config = new Genesis.Config();
        g.config.chainID = 42;
        g.alloc = new Genesis.Alloc();
        g.alloc.initAmount = Arrays.asList(
                new Genesis.InitAmount() {{ address = "a"; balance = BigDecimal.ONE; }}
        );
        assertEquals(42, g.config.chainID);
        assertEquals(1, g.alloc.initAmount.size());
    }
}