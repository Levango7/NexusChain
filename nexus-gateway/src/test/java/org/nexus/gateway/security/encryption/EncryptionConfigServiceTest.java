package org.nexus.gateway.security.encryption;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * EncryptionConfigService 单元测试 — 加密策略配置。
 *
 * <p>测试覆盖：
 * <ul>
 *   <li>createConfig 拒绝非 AES-256-GCM 算法</li>
 *   <li>createConfig 成功创建配置</li>
 *   <li>getActiveConfig 商户级优先、无则回退全局</li>
 *   <li>updateConfig 更新配置</li>
 *   <li>parseEncryptedFields 解析加密字段列表</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EncryptionConfigService — 加密策略配置")
class EncryptionConfigServiceTest {

    @Mock
    private EncryptionConfigRepository configRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private EncryptionConfigService encryptionConfigService;

    @Nested
    @DisplayName("createConfig — 创建加密策略配置")
    class CreateConfigTest {

        @Test
        @DisplayName("使用 AES-256-GCM 算法应成功创建配置")
        void createConfig_withValidAlgorithm_shouldSucceed() {
            when(configRepository.save(any(EncryptionConfig.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            EncryptionConfig config = encryptionConfigService.createConfig(
                    "tenant-001", 100L,
                    List.of("cardNumber", "cvv"),
                    "AES-256-GCM",
                    90, true);

            assertNotNull(config);
            assertEquals("tenant-001", config.getTenantId());
            assertEquals(100L, config.getMerchantId());
            assertEquals("AES-256-GCM", config.getEncryptionAlgorithm());
            assertEquals(90, config.getKekRotationPeriodDays());
            assertTrue(config.getAppLayerEncryptionEnabled());
            verify(configRepository).save(any(EncryptionConfig.class));
        }

        @Test
        @DisplayName("使用非 AES-256-GCM 算法应抛 EncryptionException")
        void createConfig_withInvalidAlgorithm_shouldThrow() {
            EncryptionException ex = assertThrows(EncryptionException.class, () ->
                    encryptionConfigService.createConfig(
                            "tenant-001", 100L,
                            List.of("cardNumber"),
                            "AES-128-CBC",
                            90, true));

            assertEquals("UNSUPPORTED_ENCRYPTION_ALGORITHM", ex.getErrorCode());
            verify(configRepository, never()).save(any());
        }

        @Test
        @DisplayName("使用 AES-256-CBC 算法应抛 EncryptionException")
        void createConfig_withAes256Cbc_shouldThrow() {
            assertThrows(EncryptionException.class, () ->
                    encryptionConfigService.createConfig(
                            "tenant-001", null,
                            List.of("cardNumber"),
                            "AES-256-CBC",
                            90, true));
        }

        @Test
        @DisplayName("使用 RSA 算法应抛 EncryptionException")
        void createConfig_withRsa_shouldThrow() {
            assertThrows(EncryptionException.class, () ->
                    encryptionConfigService.createConfig(
                            "tenant-001", null,
                            List.of("cardNumber"),
                            "RSA-2048",
                            90, true));
        }

        @Test
        @DisplayName("kekRotationPeriodDays 为 null 时应使用默认值 90")
        void createConfig_nullRotationPeriod_shouldUseDefault() {
            when(configRepository.save(any(EncryptionConfig.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            EncryptionConfig config = encryptionConfigService.createConfig(
                    "tenant-001", null,
                    List.of("cardNumber"),
                    "AES-256-GCM",
                    null, null);

            assertEquals(90, config.getKekRotationPeriodDays());
            assertTrue(config.getAppLayerEncryptionEnabled());
        }

        @Test
        @DisplayName("merchantId 为 null 表示全局策略")
        void createConfig_nullMerchantId_shouldCreateGlobalConfig() {
            when(configRepository.save(any(EncryptionConfig.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            EncryptionConfig config = encryptionConfigService.createConfig(
                    "tenant-001", null,
                    List.of("cardNumber"),
                    "AES-256-GCM",
                    90, true);

            assertNull(config.getMerchantId(), "merchantId 为 null 表示全局策略");
        }

        @Test
        @DisplayName("encryptedFields 应以 JSON 格式存储")
        void createConfig_shouldSerializeFieldsAsJson() {
            when(configRepository.save(any(EncryptionConfig.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            EncryptionConfig config = encryptionConfigService.createConfig(
                    "tenant-001", 100L,
                    List.of("cardNumber", "cvv", "cardHolderName"),
                    "AES-256-GCM",
                    90, true);

            assertNotNull(config.getEncryptedFields());
            assertTrue(config.getEncryptedFields().contains("cardNumber"));
            assertTrue(config.getEncryptedFields().contains("cvv"));
            assertTrue(config.getEncryptedFields().contains("cardHolderName"));
        }
    }

    @Nested
    @DisplayName("getActiveConfig — 获取生效配置")
    class GetActiveConfigTest {

        @Test
        @DisplayName("商户级配置存在时应优先返回商户级配置")
        void getActiveConfig_merchantConfigExists_shouldReturnMerchantConfig() {
            EncryptionConfig merchantConfig = new EncryptionConfig();
            merchantConfig.setTenantId("tenant-001");
            merchantConfig.setMerchantId(100L);

            EncryptionConfig globalConfig = new EncryptionConfig();
            globalConfig.setTenantId("tenant-001");
            globalConfig.setMerchantId(null);

            when(configRepository.findByTenantIdAndMerchantId("tenant-001", 100L))
                    .thenReturn(Optional.of(merchantConfig));

            Optional<EncryptionConfig> result =
                    encryptionConfigService.getActiveConfig("tenant-001", 100L);

            assertTrue(result.isPresent());
            assertEquals(100L, result.get().getMerchantId(),
                    "应优先返回商户级配置");
            verify(configRepository, never())
                    .findByTenantIdAndMerchantIdIsNull(any());
        }

        @Test
        @DisplayName("无商户级配置时应回退全局配置")
        void getActiveConfig_noMerchantConfig_shouldFallbackToGlobal() {
            EncryptionConfig globalConfig = new EncryptionConfig();
            globalConfig.setTenantId("tenant-001");
            globalConfig.setMerchantId(null);

            when(configRepository.findByTenantIdAndMerchantId("tenant-001", 100L))
                    .thenReturn(Optional.empty());
            when(configRepository.findByTenantIdAndMerchantIdIsNull("tenant-001"))
                    .thenReturn(Optional.of(globalConfig));

            Optional<EncryptionConfig> result =
                    encryptionConfigService.getActiveConfig("tenant-001", 100L);

            assertTrue(result.isPresent());
            assertNull(result.get().getMerchantId(),
                    "无商户级配置时应回退全局配置");
        }

        @Test
        @DisplayName("无任何配置时应返回 empty")
        void getActiveConfig_noConfigAtAll_shouldReturnEmpty() {
            when(configRepository.findByTenantIdAndMerchantId("tenant-001", 100L))
                    .thenReturn(Optional.empty());
            when(configRepository.findByTenantIdAndMerchantIdIsNull("tenant-001"))
                    .thenReturn(Optional.empty());

            Optional<EncryptionConfig> result =
                    encryptionConfigService.getActiveConfig("tenant-001", 100L);

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("getGlobalConfig — 获取全局配置")
    class GetGlobalConfigTest {

        @Test
        @DisplayName("存在全局配置时应返回")
        void getGlobalConfig_shouldReturnGlobalConfig() {
            EncryptionConfig globalConfig = new EncryptionConfig();
            globalConfig.setTenantId("tenant-001");
            globalConfig.setMerchantId(null);

            when(configRepository.findByTenantIdAndMerchantIdIsNull("tenant-001"))
                    .thenReturn(Optional.of(globalConfig));

            Optional<EncryptionConfig> result =
                    encryptionConfigService.getGlobalConfig("tenant-001");

            assertTrue(result.isPresent());
            assertNull(result.get().getMerchantId());
        }
    }

    @Nested
    @DisplayName("updateConfig — 更新加密策略配置")
    class UpdateConfigTest {

        @Test
        @DisplayName("更新不存在的配置应抛 EncryptionException")
        void updateConfig_notFound_shouldThrow() {
            when(configRepository.findById(999L))
                    .thenReturn(Optional.empty());

            EncryptionException ex = assertThrows(EncryptionException.class, () ->
                    encryptionConfigService.updateConfig(999L,
                            List.of("cardNumber"), "AES-256-GCM", 60, false));

            assertEquals("CONFIG_NOT_FOUND", ex.getErrorCode());
        }

        @Test
        @DisplayName("更新为非 AES-256-GCM 算法应抛 EncryptionException")
        void updateConfig_invalidAlgorithm_shouldThrow() {
            EncryptionConfig existing = new EncryptionConfig();
            existing.setId(1L);

            when(configRepository.findById(1L))
                    .thenReturn(Optional.of(existing));

            assertThrows(EncryptionException.class, () ->
                    encryptionConfigService.updateConfig(1L,
                            List.of("cardNumber"), "DES", 60, false));
        }

        @Test
        @DisplayName("成功更新配置字段")
        void updateConfig_shouldUpdateFields() {
            EncryptionConfig existing = new EncryptionConfig();
            existing.setId(1L);
            existing.setTenantId("tenant-001");
            existing.setEncryptionAlgorithm("AES-256-GCM");
            existing.setKekRotationPeriodDays(90);
            existing.setAppLayerEncryptionEnabled(true);

            when(configRepository.findById(1L))
                    .thenReturn(Optional.of(existing));
            when(configRepository.save(any(EncryptionConfig.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            EncryptionConfig updated = encryptionConfigService.updateConfig(1L,
                    List.of("cardNumber", "cvv"),
                    "AES-256-GCM", 60, false);

            assertEquals(60, updated.getKekRotationPeriodDays());
            assertFalse(updated.getAppLayerEncryptionEnabled());
            verify(configRepository).save(existing);
        }

        @Test
        @DisplayName("仅更新部分字段（null 参数不覆盖）")
        void updateConfig_partialUpdate_shouldNotOverwriteNulls() {
            EncryptionConfig existing = new EncryptionConfig();
            existing.setId(1L);
            existing.setKekRotationPeriodDays(90);
            existing.setAppLayerEncryptionEnabled(true);

            when(configRepository.findById(1L))
                    .thenReturn(Optional.of(existing));
            when(configRepository.save(any(EncryptionConfig.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            EncryptionConfig updated = encryptionConfigService.updateConfig(1L,
                    null, null, 60, null);

            assertEquals(60, updated.getKekRotationPeriodDays(), "应更新轮换周期");
            assertTrue(updated.getAppLayerEncryptionEnabled(), "null 参数不应覆盖原值");
        }
    }

    @Nested
    @DisplayName("parseEncryptedFields — 解析加密字段列表")
    class ParseEncryptedFieldsTest {

        @Test
        @DisplayName("正确 JSON 应解析为字段列表")
        void parseEncryptedFields_validJson_shouldReturnList() {
            EncryptionConfig config = new EncryptionConfig();
            config.setEncryptedFields("[\"cardNumber\",\"cvv\",\"cardHolderName\"]");

            List<String> fields = encryptionConfigService.parseEncryptedFields(config);

            assertEquals(3, fields.size());
            assertTrue(fields.contains("cardNumber"));
            assertTrue(fields.contains("cvv"));
            assertTrue(fields.contains("cardHolderName"));
        }

        @Test
        @DisplayName("null config 应返回空列表")
        void parseEncryptedFields_nullConfig_shouldReturnEmpty() {
            List<String> fields = encryptionConfigService.parseEncryptedFields(null);

            assertTrue(fields.isEmpty());
        }

        @Test
        @DisplayName("null encryptedFields 应返回空列表")
        void parseEncryptedFields_nullFields_shouldReturnEmpty() {
            EncryptionConfig config = new EncryptionConfig();
            config.setEncryptedFields(null);

            List<String> fields = encryptionConfigService.parseEncryptedFields(config);

            assertTrue(fields.isEmpty());
        }

        @Test
        @DisplayName("无效 JSON 应返回空列表（不抛异常）")
        void parseEncryptedFields_invalidJson_shouldReturnEmpty() {
            EncryptionConfig config = new EncryptionConfig();
            config.setEncryptedFields("not-a-json");

            List<String> fields = encryptionConfigService.parseEncryptedFields(config);

            assertTrue(fields.isEmpty(), "无效 JSON 应返回空列表而非抛异常");
        }
    }

    @Nested
    @DisplayName("findById — 按配置 ID 查询")
    class FindByIdTest {

        @Test
        @DisplayName("存在时应返回配置")
        void findById_exists_shouldReturnConfig() {
            EncryptionConfig config = new EncryptionConfig();
            config.setId(1L);

            when(configRepository.findById(1L))
                    .thenReturn(Optional.of(config));

            Optional<EncryptionConfig> result = encryptionConfigService.findById(1L);

            assertTrue(result.isPresent());
            assertEquals(1L, result.get().getId());
        }

        @Test
        @DisplayName("不存在时应返回 empty")
        void findById_notExists_shouldReturnEmpty() {
            when(configRepository.findById(999L))
                    .thenReturn(Optional.empty());

            Optional<EncryptionConfig> result = encryptionConfigService.findById(999L);

            assertTrue(result.isEmpty());
        }
    }
}