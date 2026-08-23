package org.nexus.gateway.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.nexus.gateway.model.MerchantKeypairEntry;
import org.nexus.gateway.repository.MerchantKeypairRepository;

import java.nio.file.Path;
import java.util.Base64;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * KeyManager 实现单元测试：Sandbox / LocalFile / Vault。
 *
 * <p>B-14 修复后 VaultKeyManager 构造函数要求注入 {@link MerchantKeypairRepository}，
 * 本测试使用 Mockito Mock 该依赖。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KeyManagerTest {

    @Mock
    private MerchantKeypairRepository keypairRepository;

    /** LocalFileKeyManager 构造函数要求的 Base64 编码 32 字节 AES-256 加密密钥 */
    private static final String VALID_ENC_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    // === SandboxKeyManager ===

    @Test
    @DisplayName("SandboxKeyManager: 首次获取自动生成确定性 mock key")
    void sandbox_autoGenerate() {
        SandboxKeyManager km = new SandboxKeyManager();
        Long mid = 1001L;
        String pub = km.getPublicKey(mid);
        String priv = km.getPrivateKey(mid);

        assertNotNull(pub);
        assertTrue(pub.startsWith("04"));
        assertNotNull(priv);
        // 同一 merchantId 二次获取返回相同 key
        assertEquals(pub, km.getPublicKey(mid));
        assertEquals(priv, km.getPrivateKey(mid));
    }

    @Test
    @DisplayName("SandboxKeyManager: storeKeypair 覆盖自动生成")
    void sandbox_storeKeypair() {
        SandboxKeyManager km = new SandboxKeyManager();
        km.storeKeypair(2002L, "pub-custom", "priv-custom");
        assertEquals("pub-custom", km.getPublicKey(2002L));
        assertEquals("priv-custom", km.getPrivateKey(2002L));
        assertTrue(km.hasKeypair(2002L));
    }

    @Test
    @DisplayName("SandboxKeyManager: hasKeypair 反映存储状态")
    void sandbox_hasKeypair() {
        SandboxKeyManager km = new SandboxKeyManager();
        assertFalse(km.hasKeypair(3003L));
        km.getPublicKey(3003L); // 触发自动生成
        assertTrue(km.hasKeypair(3003L));
    }

    // === LocalFileKeyManager ===

    @Test
    @DisplayName("LocalFileKeyManager: store/get/has 基本契约（@TempDir 持久化）")
    void localFile_storeAndGet(@TempDir Path dir) throws Exception {
        Path keyFile = dir.resolve("keystore.properties");
        LocalFileKeyManager km = new LocalFileKeyManager(keyFile.toString(), VALID_ENC_KEY);

        assertFalse(km.hasKeypair(1L));
        km.storeKeypair(1L, "pub-1", "priv-1");
        assertTrue(km.hasKeypair(1L));
        assertEquals("pub-1", km.getPublicKey(1L));
        assertEquals("priv-1", km.getPrivateKey(1L));

        // 重新加载应能从文件恢复
        LocalFileKeyManager km2 = new LocalFileKeyManager(keyFile.toString(), VALID_ENC_KEY);
        assertTrue(km2.hasKeypair(1L));
        assertEquals("pub-1", km2.getPublicKey(1L));
    }

    @Test
    @DisplayName("LocalFileKeyManager: 默认路径（空字符串）使用 tmpdir")
    void localFile_defaultPath() {
        LocalFileKeyManager km = new LocalFileKeyManager("", VALID_ENC_KEY);
        assertNotNull(km);
        // 不抛异常即可
        km.hasKeypair(99L);
    }

    @Test
    @DisplayName("LocalFileKeyManager: 未存储的 merchantId 返回 null")
    void localFile_unknownMerchant() {
        LocalFileKeyManager km = new LocalFileKeyManager("", VALID_ENC_KEY);
        assertNull(km.getPublicKey(99999L));
        assertNull(km.getPrivateKey(99999L));
    }

    // === VaultKeyManager ===

    @Test
    @DisplayName("VaultKeyManager: 构造要求 32 字节 master key")
    void vault_validMasterKey() {
        byte[] key = new byte[32];
        for (int i = 0; i < 32; i++) key[i] = (byte) i;
        String b64 = Base64.getEncoder().encodeToString(key);
        when(keypairRepository.findAll()).thenReturn(Collections.emptyList());
        VaultKeyManager km = new VaultKeyManager(b64, keypairRepository);
        assertNotNull(km);
    }

    @Test
    @DisplayName("VaultKeyManager: 空 master key 抛异常")
    void vault_emptyMasterKey() {
        assertThrows(IllegalStateException.class, () -> new VaultKeyManager("", keypairRepository));
    }

    @Test
    @DisplayName("VaultKeyManager: 非 32 字节 master key 抛异常")
    void vault_shortMasterKey() {
        String b64 = Base64.getEncoder().encodeToString(new byte[16]);
        assertThrows(IllegalStateException.class, () -> new VaultKeyManager(b64, keypairRepository));
    }

    @Test
    @DisplayName("VaultKeyManager: storeKeypair 加密存储后可解密恢复")
    void vault_storeAndDecrypt() {
        byte[] key = new byte[32];
        for (int i = 0; i < 32; i++) key[i] = (byte) (i + 1);
        when(keypairRepository.findAll()).thenReturn(Collections.emptyList());
        when(keypairRepository.findByMerchantId(any())).thenReturn(Optional.empty());
        when(keypairRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        VaultKeyManager km = new VaultKeyManager(Base64.getEncoder().encodeToString(key), keypairRepository);

        km.storeKeypair(5005L, "pub-secret", "priv-secret");
        assertTrue(km.hasKeypair(5005L));
        assertEquals("pub-secret", km.getPublicKey(5005L));
        assertEquals("priv-secret", km.getPrivateKey(5005L));
    }

    @Test
    @DisplayName("VaultKeyManager: 未存储的 merchantId 返回 null")
    void vault_unknownMerchant() {
        byte[] key = new byte[32];
        String b64 = Base64.getEncoder().encodeToString(key);
        when(keypairRepository.findAll()).thenReturn(Collections.emptyList());
        VaultKeyManager km = new VaultKeyManager(b64, keypairRepository);
        assertNull(km.getPublicKey(6006L));
        assertNull(km.getPrivateKey(6006L));
        assertFalse(km.hasKeypair(6006L));
    }
}