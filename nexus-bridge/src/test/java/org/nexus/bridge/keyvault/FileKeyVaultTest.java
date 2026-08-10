package org.nexus.bridge.keyvault;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link FileKeyVault} 单元测试：覆盖密钥生成、存储、签名与公钥查询。
 */
class FileKeyVaultTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("init: 应创建目录并标记可用")
    void init_createsDirectoryAndMarksAvailable() {
        FileKeyVault vault = new FileKeyVault(tempDir.toString(), "test-password");
        vault.init();

        assertTrue(vault.isAvailable());
    }

    @Test
    @DisplayName("destroy: 应标记不可用并清空公钥缓存")
    void destroy_marksUnavailable() {
        FileKeyVault vault = new FileKeyVault(tempDir.toString(), "test-password");
        vault.init();
        vault.generateAndStore("validator-1");

        vault.destroy();

        assertFalse(vault.isAvailable());
        assertTrue(vault.getValidatorIds().isEmpty());
    }

    @Test
    @DisplayName("generateAndStore: 应生成密钥对并存储公钥")
    void generateAndStore_generatesKeyPair() {
        FileKeyVault vault = new FileKeyVault(tempDir.toString(), "test-password");
        vault.init();

        vault.generateAndStore("validator-1");

        String pubKey = vault.getPublicKey("validator-1");
        assertNotNull(pubKey);
        assertFalse(pubKey.isEmpty());
        Set<String> ids = vault.getValidatorIds();
        assertTrue(ids.contains("validator-1"));
    }

    @Test
    @DisplayName("getPublicKey: 未知验证者返回 null")
    void getPublicKey_unknownReturnsNull() {
        FileKeyVault vault = new FileKeyVault(tempDir.toString(), "test-password");
        vault.init();

        assertNull(vault.getPublicKey("unknown"));
    }

    @Test
    @DisplayName("sign: 应返回有效签名（十六进制字符串）")
    void sign_returnsValidSignature() {
        FileKeyVault vault = new FileKeyVault(tempDir.toString(), "test-password");
        vault.init();
        vault.generateAndStore("validator-1");

        byte[] payload = "test payload".getBytes();
        String signature = vault.sign("validator-1", payload);

        assertNotNull(signature);
        assertFalse(signature.isEmpty());
        // 签名应为十六进制字符串
        assertTrue(signature.matches("[0-9a-f]+"));
    }

    @Test
    @DisplayName("sign: vault 不可用时应抛 IllegalStateException")
    void sign_vaultUnavailableThrows() {
        FileKeyVault vault = new FileKeyVault(tempDir.toString(), "test-password");
        // 未调用 init()，available 为 false
        assertThrows(IllegalStateException.class,
                () -> vault.sign("validator-1", "payload".getBytes()));
    }

    @Test
    @DisplayName("importKey: 应导入外部密钥并存储")
    void importKey_storesExternalKey() {
        FileKeyVault vault = new FileKeyVault(tempDir.toString(), "test-password");
        vault.init();

        // 生成一个密钥对用于导入
        FileKeyVault generator = new FileKeyVault(tempDir.toString(), "other-password");
        generator.init();
        generator.generateAndStore("temp");
        // 直接用 vault 生成并获取公钥
        vault.generateAndStore("validator-1");
        String pubKey = vault.getPublicKey("validator-1");

        // 导入相同公钥
        vault.importKey("validator-2", pubKey, pubKey);
        assertNotNull(vault.getPublicKey("validator-2"));
    }

    @Test
    @DisplayName("getValidatorIds: 应返回所有已注册验证者")
    void getValidatorIds_returnsAllRegistered() {
        FileKeyVault vault = new FileKeyVault(tempDir.toString(), "test-password");
        vault.init();
        vault.generateAndStore("v1");
        vault.generateAndStore("v2");
        vault.generateAndStore("v3");

        Set<String> ids = vault.getValidatorIds();
        assertEquals(3, ids.size());
        assertTrue(ids.contains("v1"));
        assertTrue(ids.contains("v2"));
        assertTrue(ids.contains("v3"));
    }

    @Test
    @DisplayName("isAvailable: 未 init 时返回 false")
    void isAvailable_falseBeforeInit() {
        FileKeyVault vault = new FileKeyVault(tempDir.toString(), "test-password");
        assertFalse(vault.isAvailable());
    }
}