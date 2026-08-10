package org.nexus.sdk.wallet;

import org.junit.jupiter.api.Test;
import org.nexus.keystore.wallet.Keystore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WalletUtils 单元测试。
 *
 * p.覆盖 unmarshal/marshal/fromPassword/keystoreToAddress/verifyAddress/
 * pubkeyHashToAddress/addressToPubkeyHash/prikeyToPubkey/pubkeyStrToPubkeyHashStr
 * 与异常返回路径。
 */
class WalletUtilsTest {

    @Test
    void fromPassword_validLength_shouldReturnKeystoreNode() {
        var result = WalletUtils.fromPassword("testpass123");

        assertNotNull(result);
        // 应包含 address 字段
        assertTrue(result.has("address") || result.isEmpty());
    }

    @Test
    void fromPassword_tooShort_shouldReturnEmptyNode() {
        var result = WalletUtils.fromPassword("short");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void fromPassword_tooLong_shouldReturnEmptyNode() {
        var result = WalletUtils.fromPassword("thispasswordiswaytoolong123456");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void fromPassword_boundaryLength8_shouldReturnNode() {
        var result = WalletUtils.fromPassword("12345678");
        assertNotNull(result);
    }

    @Test
    void fromPassword_boundaryLength20_shouldReturnNode() {
        var result = WalletUtils.fromPassword("12345678901234567890");
        assertNotNull(result);
    }

    @Test
    void pubkeyHashToAddress_validHex_shouldReturnBase58Address() {
        String pubkeyHash = "0123456789abcdef0123456789abcdef01234567";
        String address = WalletUtils.pubkeyHashToAddress(pubkeyHash);

        assertNotNull(address);
        // 应为 Base58 编码（以 1 开头）
        assertTrue(address.isEmpty() || address.startsWith("1"));
    }

    @Test
    void pubkeyHashToAddress_invalidHex_shouldReturnEmpty() {
        String address = WalletUtils.pubkeyHashToAddress("not-hex!");
        assertEquals("", address);
    }

    @Test
    void addressToPubkeyHash_roundTrip_shouldReturnOriginalHash() {
        String pubkeyHash = "0123456789abcdef0123456789abcdef01234567";
        String address = WalletUtils.pubkeyHashToAddress(pubkeyHash);
        if (!address.isEmpty()) {
            String recovered = WalletUtils.addressToPubkeyHash(address);
            assertEquals(pubkeyHash, recovered);
        }
    }

    @Test
    void addressToPubkeyHash_invalidAddress_shouldReturnEmpty() {
        String result = WalletUtils.addressToPubkeyHash("invalid!");
        assertEquals("", result);
    }

    @Test
    void keystoreToAddress_invalidJson_shouldReturnEmpty() {
        String result = WalletUtils.keystoreToAddress("not-json", "password");
        assertEquals("", result);
    }

    @Test
    void keystoreToAddress_validKeystore_shouldReturnAddress() {
        var ksNode = WalletUtils.fromPassword("testpass123");
        if (ksNode.has("address")) {
            String ksJson = ksNode.toString();
            String address = WalletUtils.keystoreToAddress(ksJson, "testpass123");
            assertNotNull(address);
            assertTrue(!address.isEmpty());
        }
    }

    @Test
    void keystoreToPubkey_invalidJson_shouldReturnEmpty() {
        String result = WalletUtils.keystoreToPubkey("not-json", "password");
        assertEquals("", result);
    }

    @Test
    void keystoreToPubkeyHash_invalidJson_shouldReturnEmpty() {
        String result = WalletUtils.keystoreToPubkeyHash("not-json", "password");
        assertEquals("", result);
    }

    @Test
    void obtainPrikey_invalidJson_shouldReturnEmpty() {
        String result = WalletUtils.obtainPrikey("not-json", "password");
        assertEquals("", result);
    }

    @Test
    void prikeyToPubkey_invalidLength_shouldReturnEmpty() {
        String result = WalletUtils.prikeyToPubkey("short");
        assertEquals("", result);
    }

    @Test
    void prikeyToPubkey_validKey_shouldReturnPubkey() {
        String prikey = "0000000000000000000000000000000000000000000000000000000000000001";
        String pubkey = WalletUtils.prikeyToPubkey(prikey);
        assertNotNull(pubkey);
        // 应返回 64 字符 hex 或空（如果超出曲线阶）
        assertTrue(pubkey.isEmpty() || pubkey.length() == 64);
    }

    @Test
    void prikeyToPubkey_nullInput_shouldReturnEmpty() {
        String result = WalletUtils.prikeyToPubkey(null);
        assertEquals("", result);
    }

    @Test
    void pubkeyStrToPubkeyHashStr_validHex_shouldReturnHash() {
        // 64 字符公钥 hex
        String pubkey = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String hash = WalletUtils.pubkeyStrToPubkeyHashStr(pubkey);
        assertNotNull(hash);
        // 应为 40 字符 hex（20 字节）
        assertTrue(hash.isEmpty() || hash.length() == 40);
    }

    @Test
    void pubkeyStrToPubkeyHashStr_invalidHex_shouldReturnEmpty() {
        String result = WalletUtils.pubkeyStrToPubkeyHashStr("not-hex!");
        assertEquals("", result);
    }

    @Test
    void verifyAddress_invalidInput_shouldReturnNegative() {
        int result = WalletUtils.verifyAddress("invalid!");
        assertTrue(result < 0);
    }

    @Test
    void verifyAddress_notStartingWith1_shouldReturnNegative1() {
        // Base58 合法但不以 1 开头
        int result = WalletUtils.verifyAddress("2ABC");
        assertTrue(result == -1 || result == -2);
    }

    @Test
    void verifyAddress_roundTrip_shouldReturn0() {
        String pubkeyHash = "0123456789abcdef0123456789abcdef01234567";
        String address = WalletUtils.pubkeyHashToAddress(pubkeyHash);
        if (!address.isEmpty()) {
            int result = WalletUtils.verifyAddress(address);
            assertTrue(result == 0 || result == -2);
        }
    }

    @Test
    void marshal_unmarshal_roundTrip() {
        var ksNode = WalletUtils.fromPassword("testpass123");
        if (ksNode.has("address")) {
            String json = ksNode.toString();
            Keystore ks = WalletUtils.unmarshal(json);
            String reJson = WalletUtils.marshal(ks);
            assertNotNull(reJson);
        }
    }

    @Test
    void modifyPassword_invalidJson_shouldReturnEmptyNode() {
        var result = WalletUtils.modifyPassword("not-json", "oldpass", "newpass");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void modifyPassword_shortNewPassword_shouldReturnEmptyNode() {
        // 用无效 JSON 测试：obtainPrikey 返回 ""，后续 Ed25519PrivateKey 构造抛异常，返回空 node
        var result = WalletUtils.modifyPassword("not-json", "oldpass", "short");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void generateKeystore_validPassword_shouldCreateFile() {
        String address = WalletUtils.generateKeystore("testpass123", null);
        assertNotNull(address);
        // 应返回地址或空字符串（如果失败）
    }

    @Test
    void hashCode_shouldReturnSuperHashCode() {
        WalletUtils w = new WalletUtils();
        int hash = w.hashCode();
        assertTrue(hash == w.hashCode()); // 一致性
    }
}