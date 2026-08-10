package org.nexus.core.account;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Account} 单元测试。
 */
class AccountTest {

    @Test
    void defaultConstructorCreatesEmpty() {
        Account a = new Account();
        assertEquals(0, a.getBlockHeight());
        assertNull(a.getPubkeyHash());
        assertEquals(0, a.getNonce());
        assertEquals(0, a.getBalance());
    }

    @Test
    void fullConstructorSetsFields() {
        byte[] pubkeyHash = new byte[]{1, 2, 3};
        Account a = new Account(100, pubkeyHash, 5, 1000L, 200L, 50L, 10L);
        assertEquals(100, a.getBlockHeight());
        assertArrayEquals(pubkeyHash, a.getPubkeyHash());
        assertEquals(5, a.getNonce());
        assertEquals(1000L, a.getBalance());
        assertEquals(200L, a.getIncubatecost());
        assertEquals(50L, a.getMortgage());
        assertEquals(10L, a.getVote());
    }

    @Test
    void settersUpdateFields() {
        Account a = new Account();
        a.setBlockHeight(50);
        a.setPubkeyHash(new byte[]{9});
        a.setNonce(3);
        a.setBalance(500);
        a.setIncubatecost(100);
        a.setMortgage(20);
        a.setVote(5);
        assertEquals(50, a.getBlockHeight());
        assertEquals(3, a.getNonce());
        assertEquals(500, a.getBalance());
        assertEquals(100, a.getIncubatecost());
        assertEquals(20, a.getMortgage());
        assertEquals(5, a.getVote());
    }

    @Test
    void getIdMergesPubkeyHashAndBlockHeight() {
        byte[] pubkeyHash = new byte[]{1, 2, 3};
        Account a = new Account(100, pubkeyHash, 0, 0, 0, 0, 0);
        byte[] id = a.getId();
        // pubkeyHash(3) + uint32(4) = 7 bytes
        assertEquals(7, id.length);
        assertArrayEquals(pubkeyHash, java.util.Arrays.copyOf(id, 3));
    }

    @Test
    void getIdHexStringIsHexEncoded() {
        byte[] pubkeyHash = new byte[]{0x01, 0x02};
        Account a = new Account(0, pubkeyHash, 0, 0, 0, 0, 0);
        String hex = a.getIdHexString();
        assertNotNull(hex);
        assertFalse(hex.isEmpty());
    }

    @Test
    void copyProducesEqualFieldAccount() {
        byte[] pubkeyHash = new byte[]{1, 2};
        Account a = new Account(10, pubkeyHash, 3, 100, 50, 20, 5);
        Account b = a.copy();
        assertEquals(a.getBlockHeight(), b.getBlockHeight());
        assertArrayEquals(a.getPubkeyHash(), b.getPubkeyHash());
        assertEquals(a.getNonce(), b.getNonce());
        assertEquals(a.getBalance(), b.getBalance());
        assertEquals(a.getIncubatecost(), b.getIncubatecost());
        assertEquals(a.getMortgage(), b.getMortgage());
        assertEquals(a.getVote(), b.getVote());
    }

    @Test
    void setIdStoresValue() throws Exception {
        Account a = new Account();
        byte[] id = new byte[]{9, 8, 7};
        a.setId(id);
        // id 字段通过反射验证（getId() 实际从 pubkeyHash+blockHeight 计算）
        java.lang.reflect.Field f = Account.class.getDeclaredField("id");
        f.setAccessible(true);
        assertArrayEquals(id, (byte[]) f.get(a));
    }
}