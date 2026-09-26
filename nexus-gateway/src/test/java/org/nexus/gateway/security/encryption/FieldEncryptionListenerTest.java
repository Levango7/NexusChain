package org.nexus.gateway.security.encryption;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * FieldEncryptionListener 单元测试 — JPA EntityListener。
 *
 * <p>测试覆盖：
 * <ul>
 *   <li>@PrePersist 加密、@PostLoad 解密往返</li>
 *   <li>旧数据无加密标记时直接返回明文（渐进式迁移）</li>
 *   <li>KEK 不可用时 fail-closed 抛异常</li>
 *   <li>null entity 不抛异常</li>
 *   <li>无租户上下文时跳过加密</li>
 * </ul>
 *
 * <p><b>经验参考</b>：@PrePersist 在 JPA mock 环境中不会被自动触发，
 * 需手动调用 listener.encrypt(entity) 模拟。来源：经验 2026-09-26-jpa-prepersist-mock-not-triggered</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FieldEncryptionListener — JPA EntityListener")
class FieldEncryptionListenerTest {

    @Mock
    private EncryptionService encryptionService;

    @Mock
    private EncryptionConfigService encryptionConfigService;

    @Mock
    private KeyManagementService keyManagementService;

    @Mock
    private EncryptionKeyMetadataRepository metadataRepository;

    @InjectMocks
    private FieldEncryptionListener listener;

    /** 测试用实体 — 模拟含敏感字段的 JPA Entity */
    static class TestEntity {
        private String tenantId = "tenant-001";
        private Long merchantId = 100L;
        private String cardNumber;
        private String cardHolderName;

        public String getTenantId() { return tenantId; }
        public void setTenantId(String tenantId) { this.tenantId = tenantId; }
        public Long getMerchantId() { return merchantId; }
        public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
        public String getCardNumber() { return cardNumber; }
        public void setCardNumber(String cardNumber) { this.cardNumber = cardNumber; }
        public String getCardHolderName() { return cardHolderName; }
        public void setCardHolderName(String cardHolderName) { this.cardHolderName = cardHolderName; }
    }

    private byte[] testDek;
    private byte[] testIv;
    private byte[] testCiphertext;
    private byte[] testEncryptedDek;

    @BeforeEach
    void setUp() throws Exception {
        testDek = new byte[32];
        testIv = new byte[12];
        testCiphertext = new byte[32];
        testEncryptedDek = new byte[60];

        // 默认 mock 行为：KEK 可用
        lenient().when(keyManagementService.isAvailable()).thenReturn(true);
        lenient().when(keyManagementService.getCurrentKekVersion()).thenReturn(1);
        lenient().when(keyManagementService.generateDek()).thenReturn(testDek);
        lenient().when(keyManagementService.encryptDek(any(), eq(1))).thenReturn(testEncryptedDek);
        lenient().when(keyManagementService.decryptDekCached(anyLong(), any(), eq(1)))
                .thenReturn(testDek);
    }

    /**
     * 构造加密标记格式的值：ENC:<kekVersion>:<ivBase64>:<ciphertextBase64>
     */
    private String buildEncryptedValue(int kekVersion, byte[] iv, byte[] ciphertext) {
        return "ENC:" + kekVersion + ":" +
                Base64.getEncoder().encodeToString(iv) + ":" +
                Base64.getEncoder().encodeToString(ciphertext);
    }

    @Nested
    @DisplayName("@PrePersist — 加密")
    class EncryptTest {

        @Test
        @DisplayName("KEK 不可用时 encrypt 应抛 EncryptionException（fail-closed）")
        void encrypt_kekUnavailable_shouldThrow() {
            when(keyManagementService.isAvailable()).thenReturn(false);

            TestEntity entity = new TestEntity();
            entity.cardNumber = "4111111111111111";

            EncryptionException ex = assertThrows(EncryptionException.class, () ->
                    listener.encrypt(entity));

            assertEquals("ENCRYPTION_KEY_UNAVAILABLE", ex.getErrorCode());
        }

        @Test
        @DisplayName("null entity 不应抛异常")
        void encrypt_nullEntity_shouldNotThrow() {
            assertDoesNotThrow(() -> listener.encrypt(null));
        }

        @Test
        @DisplayName("无租户上下文时应跳过加密")
        void encrypt_noTenantId_shouldSkip() {
            TestEntity entity = new TestEntity();
            entity.tenantId = null;
            entity.cardNumber = "4111111111111111";

            listener.encrypt(entity);

            // 不应调用 configService（因为无租户上下文）
            verify(encryptionConfigService, never())
                    .getActiveConfig(any(), any());
        }

        @Test
        @DisplayName("无加密配置时应跳过加密")
        void encrypt_noConfig_shouldSkip() {
            TestEntity entity = new TestEntity();
            entity.cardNumber = "4111111111111111";

            when(encryptionConfigService.getActiveConfig("tenant-001", 100L))
                    .thenReturn(Optional.empty());

            listener.encrypt(entity);

            assertEquals("4111111111111111", entity.cardNumber,
                    "无配置时字段应保持明文");
            verify(encryptionService, never()).encrypt(any(), any());
        }

        @Test
        @DisplayName("加密配置中字段列表为空时应跳过")
        void encrypt_emptyFieldsList_shouldSkip() {
            TestEntity entity = new TestEntity();
            entity.cardNumber = "4111111111111111";

            EncryptionConfig config = new EncryptionConfig();
            when(encryptionConfigService.getActiveConfig("tenant-001", 100L))
                    .thenReturn(Optional.of(config));
            when(encryptionConfigService.parseEncryptedFields(config))
                    .thenReturn(List.of());

            listener.encrypt(entity);

            assertEquals("4111111111111111", entity.cardNumber,
                    "字段列表为空时应保持明文");
        }

        @Test
        @DisplayName("成功加密后字段值应以 ENC: 前缀标记")
        void encrypt_success_shouldMarkWithEncPrefix() {
            TestEntity entity = new TestEntity();
            entity.cardNumber = "4111111111111111";

            EncryptionConfig config = new EncryptionConfig();
            when(encryptionConfigService.getActiveConfig("tenant-001", 100L))
                    .thenReturn(Optional.of(config));
            when(encryptionConfigService.parseEncryptedFields(config))
                    .thenReturn(List.of("cardNumber"));

            // mock 加密服务返回 EncryptedField
            EncryptedField encryptedField = new EncryptedField(testCiphertext, testIv);
            when(encryptionService.encrypt("4111111111111111", testDek))
                    .thenReturn(encryptedField);

            // mock metadataRepository：字段无已有元数据
            when(metadataRepository.findByTenantIdAndFieldNameAndKekVersion(
                    "tenant-001", "cardNumber", 1))
                    .thenReturn(Optional.empty());

            listener.encrypt(entity);

            assertTrue(entity.cardNumber.startsWith("ENC:"),
                    "加密后字段值应以 ENC: 前缀标记");
            assertTrue(entity.cardNumber.contains(
                    Base64.getEncoder().encodeToString(testIv)),
                    "加密值中应包含 IV");
        }

        @Test
        @DisplayName("已加密的字段（ENC: 前缀）不应重复加密")
        void encrypt_alreadyEncrypted_shouldSkip() {
            TestEntity entity = new TestEntity();
            entity.cardNumber = buildEncryptedValue(1, testIv, testCiphertext);

            EncryptionConfig config = new EncryptionConfig();
            when(encryptionConfigService.getActiveConfig("tenant-001", 100L))
                    .thenReturn(Optional.of(config));
            when(encryptionConfigService.parseEncryptedFields(config))
                    .thenReturn(List.of("cardNumber"));

            listener.encrypt(entity);

            verify(encryptionService, never()).encrypt(any(), any());
            // 值保持不变
            assertTrue(entity.cardNumber.startsWith("ENC:"));
        }

        @Test
        @DisplayName("null 字段值应跳过加密")
        void encrypt_nullFieldValue_shouldSkip() {
            TestEntity entity = new TestEntity();
            entity.cardNumber = null;

            EncryptionConfig config = new EncryptionConfig();
            when(encryptionConfigService.getActiveConfig("tenant-001", 100L))
                    .thenReturn(Optional.of(config));
            when(encryptionConfigService.parseEncryptedFields(config))
                    .thenReturn(List.of("cardNumber"));

            listener.encrypt(entity);

            verify(encryptionService, never()).encrypt(any(), any());
        }

        @Test
        @DisplayName("空字符串字段值应跳过加密")
        void encrypt_emptyFieldValue_shouldSkip() {
            TestEntity entity = new TestEntity();
            entity.cardNumber = "";

            EncryptionConfig config = new EncryptionConfig();
            when(encryptionConfigService.getActiveConfig("tenant-001", 100L))
                    .thenReturn(Optional.of(config));
            when(encryptionConfigService.parseEncryptedFields(config))
                    .thenReturn(List.of("cardNumber"));

            listener.encrypt(entity);

            verify(encryptionService, never()).encrypt(any(), any());
        }

        @Test
        @DisplayName("加密后应保存密钥元数据")
        void encrypt_shouldSaveKeyMetadata() {
            TestEntity entity = new TestEntity();
            entity.cardNumber = "4111111111111111";

            EncryptionConfig config = new EncryptionConfig();
            when(encryptionConfigService.getActiveConfig("tenant-001", 100L))
                    .thenReturn(Optional.of(config));
            when(encryptionConfigService.parseEncryptedFields(config))
                    .thenReturn(List.of("cardNumber"));

            EncryptedField encryptedField = new EncryptedField(testCiphertext, testIv);
            when(encryptionService.encrypt("4111111111111111", testDek))
                    .thenReturn(encryptedField);
            when(metadataRepository.findByTenantIdAndFieldNameAndKekVersion(
                    "tenant-001", "cardNumber", 1))
                    .thenReturn(Optional.empty());

            listener.encrypt(entity);

            verify(metadataRepository).save(any(EncryptionKeyMetadata.class));
        }

        @Test
        @DisplayName("已存在密钥元数据时不重复创建")
        void encrypt_existingMetadata_shouldNotDuplicate() {
            TestEntity entity = new TestEntity();
            entity.cardNumber = "4111111111111111";

            EncryptionConfig config = new EncryptionConfig();
            when(encryptionConfigService.getActiveConfig("tenant-001", 100L))
                    .thenReturn(Optional.of(config));
            when(encryptionConfigService.parseEncryptedFields(config))
                    .thenReturn(List.of("cardNumber"));

            EncryptedField encryptedField = new EncryptedField(testCiphertext, testIv);
            when(encryptionService.encrypt("4111111111111111", testDek))
                    .thenReturn(encryptedField);

            // 已存在元数据
            EncryptionKeyMetadata existing = new EncryptionKeyMetadata();
            existing.setId(1L);
            when(metadataRepository.findByTenantIdAndFieldNameAndKekVersion(
                    "tenant-001", "cardNumber", 1))
                    .thenReturn(Optional.of(existing));

            listener.encrypt(entity);

            verify(metadataRepository, never()).save(any(EncryptionKeyMetadata.class));
        }
    }

    @Nested
    @DisplayName("@PostLoad — 解密")
    class DecryptTest {

        @Test
        @DisplayName("null entity 不应抛异常")
        void decrypt_nullEntity_shouldNotThrow() {
            assertDoesNotThrow(() -> listener.decrypt(null));
        }

        @Test
        @DisplayName("无租户上下文时应跳过解密")
        void decrypt_noTenantId_shouldSkip() {
            TestEntity entity = new TestEntity();
            entity.tenantId = null;
            entity.cardNumber = buildEncryptedValue(1, testIv, testCiphertext);

            listener.decrypt(entity);

            verify(encryptionConfigService, never()).getActiveConfig(any(), any());
        }

        @Test
        @DisplayName("无加密配置时应跳过解密")
        void decrypt_noConfig_shouldSkip() {
            TestEntity entity = new TestEntity();
            entity.cardNumber = buildEncryptedValue(1, testIv, testCiphertext);

            when(encryptionConfigService.getActiveConfig("tenant-001", 100L))
                    .thenReturn(Optional.empty());

            listener.decrypt(entity);

            verify(encryptionService, never()).decrypt(any(), any(), any());
        }

        @Test
        @DisplayName("旧数据无加密标记时应直接返回明文（渐进式迁移）")
        void decrypt_noEncMarker_shouldReturnPlaintext() {
            TestEntity entity = new TestEntity();
            entity.cardNumber = "4111111111111111"; // 明文，无 ENC: 前缀

            EncryptionConfig config = new EncryptionConfig();
            when(encryptionConfigService.getActiveConfig("tenant-001", 100L))
                    .thenReturn(Optional.of(config));
            when(encryptionConfigService.parseEncryptedFields(config))
                    .thenReturn(List.of("cardNumber"));

            listener.decrypt(entity);

            assertEquals("4111111111111111", entity.cardNumber,
                    "旧数据无加密标记时应保持明文不变");
            verify(encryptionService, never()).decrypt(any(), any(), any());
        }

        @Test
        @DisplayName("加密字段解密后应还原明文")
        void decrypt_encryptedField_shouldRestorePlaintext() {
            TestEntity entity = new TestEntity();
            entity.cardNumber = buildEncryptedValue(1, testIv, testCiphertext);

            EncryptionConfig config = new EncryptionConfig();
            when(encryptionConfigService.getActiveConfig("tenant-001", 100L))
                    .thenReturn(Optional.of(config));
            when(encryptionConfigService.parseEncryptedFields(config))
                    .thenReturn(List.of("cardNumber"));

            EncryptionKeyMetadata metadata = new EncryptionKeyMetadata();
            metadata.setId(1L);
            metadata.setEncryptedDek(testEncryptedDek);
            when(metadataRepository.findByTenantIdAndFieldNameAndKekVersion(
                    "tenant-001", "cardNumber", 1))
                    .thenReturn(Optional.of(metadata));

            when(encryptionService.decrypt(testCiphertext, testIv, testDek))
                    .thenReturn("4111111111111111");

            listener.decrypt(entity);

            assertEquals("4111111111111111", entity.cardNumber,
                    "解密后应还原明文");
        }

        @Test
        @DisplayName("密钥元数据不存在时应跳过解密（字段保持密文）")
        void decrypt_metadataNotFound_shouldSkip() {
            TestEntity entity = new TestEntity();
            entity.cardNumber = buildEncryptedValue(1, testIv, testCiphertext);

            EncryptionConfig config = new EncryptionConfig();
            when(encryptionConfigService.getActiveConfig("tenant-001", 100L))
                    .thenReturn(Optional.of(config));
            when(encryptionConfigService.parseEncryptedFields(config))
                    .thenReturn(List.of("cardNumber"));

            when(metadataRepository.findByTenantIdAndFieldNameAndKekVersion(
                    "tenant-001", "cardNumber", 1))
                    .thenReturn(Optional.empty());

            listener.decrypt(entity);

            // 元数据不存在时字段保持密文不变
            assertTrue(entity.cardNumber.startsWith("ENC:"),
                    "元数据不存在时字段应保持密文");
        }

        @Test
        @DisplayName("解密失败时应将字段设为 null（fail-closed，不泄露密文）")
        void decrypt_decryptionFailure_shouldSetNull() {
            TestEntity entity = new TestEntity();
            entity.cardNumber = buildEncryptedValue(1, testIv, testCiphertext);

            EncryptionConfig config = new EncryptionConfig();
            when(encryptionConfigService.getActiveConfig("tenant-001", 100L))
                    .thenReturn(Optional.of(config));
            when(encryptionConfigService.parseEncryptedFields(config))
                    .thenReturn(List.of("cardNumber"));

            EncryptionKeyMetadata metadata = new EncryptionKeyMetadata();
            metadata.setId(1L);
            metadata.setEncryptedDek(testEncryptedDek);
            when(metadataRepository.findByTenantIdAndFieldNameAndKekVersion(
                    "tenant-001", "cardNumber", 1))
                    .thenReturn(Optional.of(metadata));

            when(encryptionService.decrypt(any(), any(), any()))
                    .thenThrow(new EncryptionException("DATA_INTEGRITY_VIOLATION", "解密失败"));

            listener.decrypt(entity);

            assertNull(entity.cardNumber,
                    "解密失败时应将字段设为 null（fail-closed，不泄露密文）");
        }

        @Test
        @DisplayName("加密字段格式错误时应跳过（不抛异常）")
        void decrypt_malformedEncValue_shouldSkip() {
            TestEntity entity = new TestEntity();
            // 格式错误：ENC: 后只有两部分而非三部分
            entity.cardNumber = "ENC:invalid-data";

            EncryptionConfig config = new EncryptionConfig();
            when(encryptionConfigService.getActiveConfig("tenant-001", 100L))
                    .thenReturn(Optional.of(config));
            when(encryptionConfigService.parseEncryptedFields(config))
                    .thenReturn(List.of("cardNumber"));

            listener.decrypt(entity);

            // 格式错误时字段保持不变，不抛异常
            assertEquals("ENC:invalid-data", entity.cardNumber);
        }

        @Test
        @DisplayName("加密配置中字段列表为空时应跳过解密")
        void decrypt_emptyFieldsList_shouldSkip() {
            TestEntity entity = new TestEntity();
            entity.cardNumber = buildEncryptedValue(1, testIv, testCiphertext);

            EncryptionConfig config = new EncryptionConfig();
            when(encryptionConfigService.getActiveConfig("tenant-001", 100L))
                    .thenReturn(Optional.of(config));
            when(encryptionConfigService.parseEncryptedFields(config))
                    .thenReturn(List.of());

            listener.decrypt(entity);

            verify(encryptionService, never()).decrypt(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("@PrePersist + @PostLoad 往返一致性")
    class RoundTripTest {

        @Test
        @DisplayName("加密后解密应还原原始明文")
        void encryptThenDecrypt_shouldRestoreOriginalPlaintext() {
            String originalPlaintext = "4111111111111111";

            // --- 加密阶段 ---
            TestEntity entity = new TestEntity();
            entity.cardNumber = originalPlaintext;

            EncryptionConfig config = new EncryptionConfig();
            when(encryptionConfigService.getActiveConfig("tenant-001", 100L))
                    .thenReturn(Optional.of(config));
            when(encryptionConfigService.parseEncryptedFields(config))
                    .thenReturn(List.of("cardNumber"));

            EncryptedField encryptedField = new EncryptedField(testCiphertext, testIv);
            when(encryptionService.encrypt(originalPlaintext, testDek))
                    .thenReturn(encryptedField);
            when(metadataRepository.findByTenantIdAndFieldNameAndKekVersion(
                    "tenant-001", "cardNumber", 1))
                    .thenReturn(Optional.empty());

            listener.encrypt(entity);

            assertTrue(entity.cardNumber.startsWith("ENC:"),
                    "加密后应以 ENC: 前缀标记");

            // --- 解密阶段 ---
            // 模拟从数据库加载（entity.cardNumber 已是加密值）
            EncryptionKeyMetadata metadata = new EncryptionKeyMetadata();
            metadata.setId(1L);
            metadata.setEncryptedDek(testEncryptedDek);
            when(metadataRepository.findByTenantIdAndFieldNameAndKekVersion(
                    "tenant-001", "cardNumber", 1))
                    .thenReturn(Optional.of(metadata));

            when(encryptionService.decrypt(testCiphertext, testIv, testDek))
                    .thenReturn(originalPlaintext);

            listener.decrypt(entity);

            assertEquals(originalPlaintext, entity.cardNumber,
                    "加密后解密应还原原始明文");
        }
    }
}