package org.nexus.gateway.apikey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link ApiKeyService} 单元测试。
 *
 * <p>覆盖创建/轮换/撤销/验证/过期/数量上限等核心逻辑。
 * 使用 Mockito mock {@link ApiKeyRepository}，不依赖数据库。</p>
 */
@ExtendWith(MockitoExtension.class)
class ApiKeyServiceTest {

    @Mock
    private ApiKeyRepository apiKeyRepository;

    private ApiKeyService apiKeyService;

    @BeforeEach
    void setUp() {
        apiKeyService = new ApiKeyService(apiKeyRepository);
        // 通过反射注入 @Value 字段（单元测试无 Spring 容器）
        setField(apiKeyService, "defaultExpireDays", 90);
        setField(apiKeyService, "maxKeysPerMerchant", 10L);
        setField(apiKeyService, "secretLengthBytes", 32);
    }

    // === 创建 ===

    @Test
    @DisplayName("创建 API Key：生成 keyId 和明文密钥，密钥哈希存储")
    void createApiKeyGeneratesKeyIdAndHashedSecret() {
        when(apiKeyRepository.countByMerchantId("merchant-1")).thenReturn(0L);
        when(apiKeyRepository.save(any(ApiKey.class))).thenAnswer(inv -> {
            ApiKey k = inv.getArgument(0);
            k.setId(1L);
            return k;
        });

        ApiKeyService.CreateApiKeyResult result =
                apiKeyService.createApiKey("merchant-1", "PAYMENTS,REFUNDS", "测试Key", null);

        ApiKey saved = result.getApiKey();
        assertNotNull(saved.getKeyId());
        assertTrue(saved.getKeyId().startsWith("ak_live_"));
        assertEquals("merchant-1", saved.getMerchantId());
        assertEquals("PAYMENTS,REFUNDS", saved.getScopes());
        assertEquals(ApiKeyStatus.ACTIVE, saved.getStatus());
        assertEquals("测试Key", saved.getDescription());
        assertNull(saved.getExpireAt());

        // 明文密钥非空，且与存储的哈希不同
        assertNotNull(result.getPlainSecret());
        assertNotEquals(result.getPlainSecret(), saved.getKeySecret());

        // 存储的是 SHA-256 哈希（64位 hex）
        assertEquals(64, saved.getKeySecret().length());
    }

    @Test
    @DisplayName("创建 API Key：达到上限时抛异常")
    void createApiKeyThrowsWhenMaxReached() {
        when(apiKeyRepository.countByMerchantId("merchant-1")).thenReturn(10L);

        assertThrows(IllegalStateException.class, () ->
                apiKeyService.createApiKey("merchant-1", "PAYMENTS", "测试", null));
    }

    @Test
    @DisplayName("创建 API Key：指定过期时间")
    void createApiKeyWithExpireAt() {
        when(apiKeyRepository.countByMerchantId("merchant-1")).thenReturn(0L);
        when(apiKeyRepository.save(any(ApiKey.class))).thenAnswer(inv -> {
            ApiKey k = inv.getArgument(0);
            k.setId(1L);
            return k;
        });

        LocalDateTime expireAt = LocalDateTime.now().plusDays(30);
        ApiKeyService.CreateApiKeyResult result =
                apiKeyService.createApiKey("merchant-1", "PAYMENTS", "短期Key", expireAt);

        assertEquals(expireAt, result.getApiKey().getExpireAt());
    }

    // === 轮换 ===

    @Test
    @DisplayName("轮换 API Key：旧 Key 标记 ROTATED，新 Key 关联 rotatedFromId")
    void rotateApiKeyMarksOldAndCreatesNew() {
        ApiKey oldKey = createActiveKey("ak_live_old123", "merchant-1", "PAYMENTS,REFUNDS");
        when(apiKeyRepository.findByKeyId("ak_live_old123")).thenReturn(Optional.of(oldKey));
        when(apiKeyRepository.save(any(ApiKey.class))).thenAnswer(inv -> {
            ApiKey k = inv.getArgument(0);
            if (k.getId() == null) k.setId(System.nanoTime());
            return k;
        });

        ApiKeyService.CreateApiKeyResult result = apiKeyService.rotateApiKey("ak_live_old123");

        // 旧 Key 标记 ROTATED
        assertEquals(ApiKeyStatus.ROTATED, oldKey.getStatus());

        // 新 Key 关联旧 Key
        ApiKey newKey = result.getApiKey();
        assertNotEquals(oldKey.getKeyId(), newKey.getKeyId());
        assertEquals("ak_live_old123", newKey.getRotatedFromId());
        assertEquals("merchant-1", newKey.getMerchantId());
        assertEquals("PAYMENTS,REFUNDS", newKey.getScopes());
        assertEquals(ApiKeyStatus.ACTIVE, newKey.getStatus());
        assertNotNull(result.getPlainSecret());
    }

    @Test
    @DisplayName("轮换 API Key：旧 Key 不存在时抛异常")
    void rotateApiKeyThrowsWhenNotFound() {
        when(apiKeyRepository.findByKeyId("nonexistent")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () ->
                apiKeyService.rotateApiKey("nonexistent"));
    }

    @Test
    @DisplayName("轮换 API Key：旧 Key 非 ACTIVE 状态时抛异常")
    void rotateApiKeyThrowsWhenNotActive() {
        ApiKey revokedKey = createActiveKey("ak_live_rev123", "merchant-1", "PAYMENTS");
        revokedKey.setStatus(ApiKeyStatus.REVOKED);
        when(apiKeyRepository.findByKeyId("ak_live_rev123")).thenReturn(Optional.of(revokedKey));

        assertThrows(IllegalStateException.class, () ->
                apiKeyService.rotateApiKey("ak_live_rev123"));
    }

    // === 撤销 ===

    @Test
    @DisplayName("撤销 API Key：标记 REVOKED，记录原因和时间")
    void revokeApiKeyMarksRevokedWithReason() {
        ApiKey activeKey = createActiveKey("ak_live_act123", "merchant-1", "PAYMENTS");
        when(apiKeyRepository.findByKeyId("ak_live_act123")).thenReturn(Optional.of(activeKey));
        when(apiKeyRepository.save(any(ApiKey.class))).thenAnswer(inv -> inv.getArgument(0));

        ApiKey revoked = apiKeyService.revokeApiKey("ak_live_act123", "安全泄露");

        assertEquals(ApiKeyStatus.REVOKED, revoked.getStatus());
        assertEquals("安全泄露", revoked.getRevokedReason());
        assertNotNull(revoked.getRevokedAt());
    }

    @Test
    @DisplayName("撤销 API Key：不存在时抛异常")
    void revokeApiKeyThrowsWhenNotFound() {
        when(apiKeyRepository.findByKeyId("nonexistent")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () ->
                apiKeyService.revokeApiKey("nonexistent", "测试"));
    }

    @Test
    @DisplayName("撤销 API Key：非 ACTIVE 状态时抛异常")
    void revokeApiKeyThrowsWhenNotActive() {
        ApiKey rotatedKey = createActiveKey("ak_live_rot123", "merchant-1", "PAYMENTS");
        rotatedKey.setStatus(ApiKeyStatus.ROTATED);
        when(apiKeyRepository.findByKeyId("ak_live_rot123")).thenReturn(Optional.of(rotatedKey));

        assertThrows(IllegalStateException.class, () ->
                apiKeyService.revokeApiKey("ak_live_rot123", "测试"));
    }

    // === 验证 ===

    @Test
    @DisplayName("验证 API Key：ACTIVE + 密钥匹配 → 通过")
    void validateApiKeySuccess() {
        ApiKey key = createActiveKey("ak_live_val123", "merchant-1", "PAYMENTS");
        // 模拟保存时已存储哈希
        when(apiKeyRepository.findByKeyId("ak_live_val123")).thenReturn(Optional.of(key));

        // 先创建一个 Key 获取明文密钥，再用该明文验证
        when(apiKeyRepository.countByMerchantId("merchant-1")).thenReturn(0L);
        when(apiKeyRepository.save(any(ApiKey.class))).thenAnswer(inv -> {
            ApiKey k = inv.getArgument(0);
            k.setId(1L);
            return k;
        });

        ApiKeyService.CreateApiKeyResult created =
                apiKeyService.createApiKey("merchant-1", "PAYMENTS", "验证测试", null);
        String plainSecret = created.getPlainSecret();

        // 用创建的 Key 验证
        when(apiKeyRepository.findByKeyId(created.getApiKey().getKeyId()))
                .thenReturn(Optional.of(created.getApiKey()));

        Optional<ApiKey> validated =
                apiKeyService.validateApiKey(created.getApiKey().getKeyId(), plainSecret);

        assertTrue(validated.isPresent());
        assertEquals(ApiKeyStatus.ACTIVE, validated.get().getStatus());
    }

    @Test
    @DisplayName("验证 API Key：密钥不匹配 → 失败")
    void validateApiKeyFailsWithWrongSecret() {
        ApiKey key = createActiveKey("ak_live_val456", "merchant-1", "PAYMENTS");
        when(apiKeyRepository.findByKeyId("ak_live_val456")).thenReturn(Optional.of(key));

        Optional<ApiKey> result =
                apiKeyService.validateApiKey("ak_live_val456", "wrong-secret");

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("验证 API Key：不存在 → 失败")
    void validateApiKeyFailsWhenNotFound() {
        when(apiKeyRepository.findByKeyId("nonexistent")).thenReturn(Optional.empty());

        Optional<ApiKey> result =
                apiKeyService.validateApiKey("nonexistent", "any-secret");

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("验证 API Key：已过期 → 自动标记 EXPIRED 并失败")
    void validateApiKeyExpiredAutoMarksAndFails() {
        ApiKey expiredKey = createActiveKey("ak_live_exp123", "merchant-1", "PAYMENTS");
        expiredKey.setExpireAt(LocalDateTime.now().minusDays(1)); // 昨天过期
        when(apiKeyRepository.findByKeyId("ak_live_exp123")).thenReturn(Optional.of(expiredKey));
        when(apiKeyRepository.save(any(ApiKey.class))).thenAnswer(inv -> inv.getArgument(0));

        // 需要用正确的密钥才能到达过期检查
        // 先创建获取明文
        when(apiKeyRepository.countByMerchantId("merchant-1")).thenReturn(0L);
        when(apiKeyRepository.save(any(ApiKey.class))).thenAnswer(inv -> {
            ApiKey k = inv.getArgument(0);
            k.setId(1L);
            return k;
        });

        // 直接构造一个已过期的 Key 并设置正确的哈希
        ApiKey key = createActiveKeyWithKnownSecret("ak_live_exp456", "merchant-1", "PAYMENTS");
        key.setExpireAt(LocalDateTime.now().minusDays(1));
        when(apiKeyRepository.findByKeyId("ak_live_exp456")).thenReturn(Optional.of(key));
        when(apiKeyRepository.save(any(ApiKey.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<ApiKey> result =
                apiKeyService.validateApiKey("ak_live_exp456", KNOWN_PLAIN_SECRET);

        assertTrue(result.isEmpty());
        assertEquals(ApiKeyStatus.EXPIRED, key.getStatus());
    }

    @Test
    @DisplayName("验证 API Key：REVOKED 状态 → 失败")
    void validateApiKeyFailsWhenRevoked() {
        ApiKey revokedKey = createActiveKey("ak_live_rev789", "merchant-1", "PAYMENTS");
        revokedKey.setStatus(ApiKeyStatus.REVOKED);
        when(apiKeyRepository.findByKeyId("ak_live_rev789")).thenReturn(Optional.of(revokedKey));

        Optional<ApiKey> result =
                apiKeyService.validateApiKey("ak_live_rev789", "any-secret");

        assertTrue(result.isEmpty());
    }

    // === 列出 ===

    @Test
    @DisplayName("列出 API Key：按商户 ID 查询")
    void listApiKeysByMerchantId() {
        ApiKey key1 = createActiveKey("ak_live_k001", "merchant-1", "PAYMENTS");
        ApiKey key2 = createActiveKey("ak_live_k002", "merchant-1", "REFUNDS");
        when(apiKeyRepository.findByMerchantIdOrderByCreatedAtDesc("merchant-1"))
                .thenReturn(List.of(key2, key1));

        List<ApiKey> keys = apiKeyService.listApiKeys("merchant-1");

        assertEquals(2, keys.size());
        assertEquals("ak_live_k002", keys.get(0).getKeyId());
        assertEquals("ak_live_k001", keys.get(1).getKeyId());
    }

    // === 更新最后使用时间 ===

    @Test
    @DisplayName("更新最后使用时间")
    void updateLastUsedSetsTimestamp() {
        ApiKey key = createActiveKey("ak_live_used1", "merchant-1", "PAYMENTS");
        when(apiKeyRepository.findByKeyId("ak_live_used1")).thenReturn(Optional.of(key));
        when(apiKeyRepository.save(any(ApiKey.class))).thenAnswer(inv -> inv.getArgument(0));

        apiKeyService.updateLastUsed("ak_live_used1");

        assertNotNull(key.getLastUsedAt());
    }

    @Test
    @DisplayName("更新最后使用时间：Key 不存在时静默忽略")
    void updateLastUsedNoOpWhenNotFound() {
        when(apiKeyRepository.findByKeyId("nonexistent")).thenReturn(Optional.empty());

        // 不抛异常
        assertDoesNotThrow(() -> apiKeyService.updateLastUsed("nonexistent"));
    }

    // === 辅助方法 ===

    private static final String KNOWN_PLAIN_SECRET = "test-secret-for-validation";

    private ApiKey createActiveKey(String keyId, String merchantId, String scopes) {
        ApiKey key = new ApiKey();
        key.setId(System.nanoTime());
        key.setKeyId(keyId);
        key.setKeySecret("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"); // 64位 hex
        key.setMerchantId(merchantId);
        key.setScopes(scopes);
        key.setStatus(ApiKeyStatus.ACTIVE);
        key.setCreatedAt(LocalDateTime.now());
        return key;
    }

    /**
     * 创建一个使用已知明文密钥的 Key（用于验证测试）。
     * 密钥哈希通过 ApiKeyService 的 hashSecret 逻辑手动计算。
     */
    private ApiKey createActiveKeyWithKnownSecret(String keyId, String merchantId, String scopes) {
        ApiKey key = new ApiKey();
        key.setId(System.nanoTime());
        key.setKeyId(keyId);
        // SHA-256("test-secret-for-validation") 的 hex
        key.setKeySecret(sha256Hex(KNOWN_PLAIN_SECRET));
        key.setMerchantId(merchantId);
        key.setScopes(scopes);
        key.setStatus(ApiKeyStatus.ACTIVE);
        key.setCreatedAt(LocalDateTime.now());
        return key;
    }

    private String sha256Hex(String input) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hashBytes.length * 2);
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("无法设置字段: " + fieldName, e);
        }
    }
}