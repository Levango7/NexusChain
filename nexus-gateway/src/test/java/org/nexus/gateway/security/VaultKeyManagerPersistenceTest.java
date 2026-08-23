package org.nexus.gateway.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.gateway.model.MerchantKeypairEntry;
import org.nexus.gateway.repository.MerchantKeypairRepository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link VaultKeyManager} 持久化测试（B-14 修复专项）。
 *
 * <p>验证 P0 修复 B-14 的核心不变量：</p>
 * <ul>
 *   <li>storeKeypair 同步 upsert 到数据库（先落库再更新内存）</li>
 *   <li>@PostConstruct loadFromDatabase 启动时从 DB 全量加载密钥到内存</li>
 *   <li>storeKeypair 后 restart（重新构造 + loadFromDatabase）能恢复密钥</li>
 *   <li>密钥不存在时 getPublicKey/getPrivateKey 返回 null</li>
 *   <li>DB 加载失败时抛 IllegalStateException（拒绝启动）</li>
 * </ul>
 *
 * <p>使用 Mockito Mock {@link MerchantKeypairRepository}，
 * 模拟 DB 行为验证持久化逻辑。</p>
 */
@ExtendWith(MockitoExtension.class)
class VaultKeyManagerPersistenceTest {

    @Mock
    private MerchantKeypairRepository keypairRepository;

    private static final String MASTER_KEY_B64;

    static {
        byte[] key = new byte[32];
        for (int i = 0; i < 32; i++) key[i] = (byte) (i + 1);
        MASTER_KEY_B64 = Base64.getEncoder().encodeToString(key);
    }

    // ==================== B-14: storeKeypair 持久化到 DB ====================

    @Test
    @DisplayName("should_persistToDb_when_storeKeypair")
    void should_persistToDb_when_storeKeypair() {
        when(keypairRepository.findAll()).thenReturn(Collections.emptyList());
        when(keypairRepository.findByMerchantId(anyLong())).thenReturn(Optional.empty());
        when(keypairRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        VaultKeyManager km = new VaultKeyManager(MASTER_KEY_B64, keypairRepository);
        km.loadFromDatabase();

        km.storeKeypair(1001L, "pub-1", "priv-1");

        // 验证 DB save 被调用
        verify(keypairRepository).findByMerchantId(1001L);
        verify(keypairRepository).save(any(MerchantKeypairEntry.class));
        // 内存中也有
        assertThat(km.hasKeypair(1001L)).isTrue();
        assertThat(km.getPublicKey(1001L)).isEqualTo("pub-1");
        assertThat(km.getPrivateKey(1001L)).isEqualTo("priv-1");
    }

    @Test
    @DisplayName("should_updateExistingEntry_when_storeKeypairForExistingMerchant")
    void should_updateExistingEntry_when_storeKeypairForExistingMerchant() {
        // 模拟 DB 中已有该 merchant 的记录
        MerchantKeypairEntry existing = new MerchantKeypairEntry();
        existing.setMerchantId(1001L);
        existing.setEncryptedKeypair("old-encrypted");

        when(keypairRepository.findAll()).thenReturn(Collections.emptyList());
        when(keypairRepository.findByMerchantId(1001L)).thenReturn(Optional.of(existing));
        when(keypairRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        VaultKeyManager km = new VaultKeyManager(MASTER_KEY_B64, keypairRepository);
        km.loadFromDatabase();

        // 重新存储应更新已有记录
        km.storeKeypair(1001L, "new-pub", "new-priv");

        // 验证调用的是已有 entry 的 save（update）
        verify(keypairRepository).findByMerchantId(1001L);
        verify(keypairRepository).save(existing); // 应保存同一个 entry 对象
        assertThat(existing.getEncryptedKeypair()).isNotEqualTo("old-encrypted");
        assertThat(km.getPublicKey(1001L)).isEqualTo("new-pub");
    }

    @Test
    @DisplayName("should_throwAndNotUpdateMemory_when_dbSaveFails")
    void should_throwAndNotUpdateMemory_when_dbSaveFails() {
        when(keypairRepository.findAll()).thenReturn(Collections.emptyList());
        when(keypairRepository.findByMerchantId(anyLong())).thenReturn(Optional.empty());
        when(keypairRepository.save(any()))
                .thenThrow(new RuntimeException("DB write failed"));

        VaultKeyManager km = new VaultKeyManager(MASTER_KEY_B64, keypairRepository);
        km.loadFromDatabase();

        // storeKeypair 应抛异常
        assertThatThrownBy(() -> km.storeKeypair(1001L, "pub", "priv"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to persist keypair")
                .hasCauseInstanceOf(RuntimeException.class);

        // 关键断言：DB 失败时内存不应被更新
        assertThat(km.hasKeypair(1001L)).isFalse();
        assertThat(km.getPublicKey(1001L)).isNull();
    }

    // ==================== B-14: @PostConstruct loadFromDatabase ====================

    @Test
    @DisplayName("should_loadFromDb_when_loadFromDatabase")
    void should_loadFromDb_when_loadFromDatabase() {
        // 模拟 DB 中已有密钥记录
        // 先用一个 VaultKeyManager 加密密钥，再模拟 DB 返回该密文
        when(keypairRepository.findAll()).thenReturn(Collections.emptyList());
        when(keypairRepository.findByMerchantId(anyLong())).thenReturn(Optional.empty());
        when(keypairRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        VaultKeyManager km1 = new VaultKeyManager(MASTER_KEY_B64, keypairRepository);
        km1.loadFromDatabase();
        km1.storeKeypair(1001L, "pub-persisted", "priv-persisted");

        // 捕获写入 DB 的密文
        org.mockito.ArgumentCaptor<MerchantKeypairEntry> captor =
                org.mockito.ArgumentCaptor.forClass(MerchantKeypairEntry.class);
        verify(keypairRepository).save(captor.capture());
        String encryptedKeypair = captor.getValue().getEncryptedKeypair();

        // 模拟 restart：新 VaultKeyManager 从 DB 加载
        MerchantKeypairEntry dbEntry = new MerchantKeypairEntry();
        dbEntry.setMerchantId(1001L);
        dbEntry.setEncryptedKeypair(encryptedKeypair);

        org.mockito.Mockito.reset(keypairRepository);
        when(keypairRepository.findAll()).thenReturn(Arrays.asList(dbEntry));

        VaultKeyManager km2 = new VaultKeyManager(MASTER_KEY_B64, keypairRepository);
        km2.loadFromDatabase();

        // 关键断言：restart 后能从 DB 恢复密钥
        assertThat(km2.hasKeypair(1001L)).isTrue();
        assertThat(km2.getPublicKey(1001L)).isEqualTo("pub-persisted");
        assertThat(km2.getPrivateKey(1001L)).isEqualTo("priv-persisted");
    }

    @Test
    @DisplayName("should_throwIllegalState_when_loadFromDatabaseFails")
    void should_throwIllegalState_when_loadFromDatabaseFails() {
        when(keypairRepository.findAll())
                .thenThrow(new RuntimeException("DB unavailable"));

        VaultKeyManager km = new VaultKeyManager(MASTER_KEY_B64, keypairRepository);

        // loadFromDatabase 失败应抛 IllegalStateException（拒绝启动）
        assertThatThrownBy(() -> km.loadFromDatabase())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to load merchant keypairs")
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("should_loadMultipleEntries_when_loadFromDatabase")
    void should_loadMultipleEntries_when_loadFromDatabase() {
        // 准备多个密钥记录
        when(keypairRepository.findAll()).thenReturn(Collections.emptyList());
        when(keypairRepository.findByMerchantId(anyLong())).thenReturn(Optional.empty());
        when(keypairRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        VaultKeyManager km1 = new VaultKeyManager(MASTER_KEY_B64, keypairRepository);
        km1.loadFromDatabase();
        km1.storeKeypair(1L, "pub-1", "priv-1");
        km1.storeKeypair(2L, "pub-2", "priv-2");
        km1.storeKeypair(3L, "pub-3", "priv-3");

        // 捕获所有写入的密文
        org.mockito.ArgumentCaptor<MerchantKeypairEntry> captor =
                org.mockito.ArgumentCaptor.forClass(MerchantKeypairEntry.class);
        verify(keypairRepository, times(3)).save(captor.capture());
        List<MerchantKeypairEntry> saved = new ArrayList<>(captor.getAllValues());

        // 模拟 restart
        org.mockito.Mockito.reset(keypairRepository);
        when(keypairRepository.findAll()).thenReturn(saved);

        VaultKeyManager km2 = new VaultKeyManager(MASTER_KEY_B64, keypairRepository);
        km2.loadFromDatabase();

        // 三个密钥都应能恢复
        assertThat(km2.hasKeypair(1L)).isTrue();
        assertThat(km2.hasKeypair(2L)).isTrue();
        assertThat(km2.hasKeypair(3L)).isTrue();
        assertThat(km2.getPublicKey(1L)).isEqualTo("pub-1");
        assertThat(km2.getPublicKey(2L)).isEqualTo("pub-2");
        assertThat(km2.getPublicKey(3L)).isEqualTo("pub-3");
        assertThat(km2.getPrivateKey(1L)).isEqualTo("priv-1");
        assertThat(km2.getPrivateKey(2L)).isEqualTo("priv-2");
        assertThat(km2.getPrivateKey(3L)).isEqualTo("priv-3");
    }

    // ==================== 密钥不存在时返回 null ====================

    @Test
    @DisplayName("should_returnNull_when_keypairNotExists")
    void should_returnNull_when_keypairNotExists() {
        when(keypairRepository.findAll()).thenReturn(Collections.emptyList());

        VaultKeyManager km = new VaultKeyManager(MASTER_KEY_B64, keypairRepository);
        km.loadFromDatabase();

        // 未存储的 merchantId 应返回 null
        assertThat(km.hasKeypair(99999L)).isFalse();
        assertThat(km.getPublicKey(99999L)).isNull();
        assertThat(km.getPrivateKey(99999L)).isNull();
    }

    @Test
    @DisplayName("should_returnFalse_when_hasKeypairForNonExistent")
    void should_returnFalse_when_hasKeypairForNonExistent() {
        when(keypairRepository.findAll()).thenReturn(Collections.emptyList());

        VaultKeyManager km = new VaultKeyManager(MASTER_KEY_B64, keypairRepository);
        km.loadFromDatabase();

        assertThat(km.hasKeypair(88888L)).isFalse();
    }

    // ==================== 加密安全性 ====================

    @Test
    @DisplayName("should_storeEncryptedNotPlaintext_when_storeKeypair")
    void should_storeEncryptedNotPlaintext_when_storeKeypair() {
        when(keypairRepository.findAll()).thenReturn(Collections.emptyList());
        when(keypairRepository.findByMerchantId(anyLong())).thenReturn(Optional.empty());
        when(keypairRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        VaultKeyManager km = new VaultKeyManager(MASTER_KEY_B64, keypairRepository);
        km.loadFromDatabase();

        km.storeKeypair(1001L, "sensitive-pub-key", "sensitive-priv-key");

        org.mockito.ArgumentCaptor<MerchantKeypairEntry> captor =
                org.mockito.ArgumentCaptor.forClass(MerchantKeypairEntry.class);
        verify(keypairRepository).save(captor.capture());
        String storedEncrypted = captor.getValue().getEncryptedKeypair();

        // 关键断言：DB 中存储的应是密文，不应包含明文
        assertThat(storedEncrypted).isNotEqualTo("sensitive-pub-key|sensitive-priv-key");
        assertThat(storedEncrypted).doesNotContain("sensitive-pub-key");
        assertThat(storedEncrypted).doesNotContain("sensitive-priv-key");
    }
}