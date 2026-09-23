package org.nexus.gateway.apikey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * API Key 服务 — 管理 API Key 的完整生命周期。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>{@link #createApiKey}：生成新 Key（keyId + keySecret），密钥 SHA-256 哈希存储，明文仅返回一次</li>
 *   <li>{@link #rotateApiKey}：轮换 Key — 创建新 Key，旧 Key 标记 ROTATED 并关联</li>
 *   <li>{@link #revokeApiKey}：撤销 Key — 标记 REVOKED，记录撤销原因与时间</li>
 *   <li>{@link #validateApiKey}：验证 Key — 状态 ACTIVE + 未过期 + 密钥哈希匹配</li>
 *   <li>{@link #listApiKeys}：列出租户的所有 Key</li>
 *   <li>{@link #updateLastUsed}：更新最后使用时间</li>
 * </ul>
 *
 * <p>安全设计：</p>
 * <ul>
 *   <li>keySecret 使用 {@link SecureRandom} 生成 32 字节随机数，Base64 编码</li>
 *   <li>存储时仅保存 SHA-256 哈希，明文仅在 {@link CreateApiKeyResult} 中返回一次</li>
 *   <li>keyId 格式：{@code "ak_live_" + UUID 前16位}，便于识别与日志追踪</li>
 * </ul>
 */
@Service
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);

    private static final String KEY_ID_PREFIX = "ak_live_";
    private static final String HASH_ALGORITHM = "SHA-256";

    private final ApiKeyRepository apiKeyRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${nexus.api-key.default-expire-days:90}")
    private int defaultExpireDays;

    @Value("${nexus.api-key.max-keys-per-merchant:10}")
    private long maxKeysPerMerchant;

    @Value("${nexus.api-key.secret-length-bytes:32}")
    private int secretLengthBytes;

    public ApiKeyService(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    /**
     * 创建新 API Key。
     *
     * <p>生成 keyId（"ak_live_" + UUID 前16位）和 keySecret（SecureRandom 32字节 Base64），
     * 密钥以 SHA-256 哈希存储。明文 keySecret 仅在返回的 {@link CreateApiKeyResult} 中可见一次。</p>
     *
     * @param merchantId  商户 ID
     * @param scopes      权限范围列表（逗号分隔的 ApiKeyScope 名称）
     * @param description Key 用途描述（可空）
     * @param expireAt    过期时间（null 表示永不过期）
     * @return 创建结果（含明文 keySecret，仅此一次可见）
     * @throws IllegalStateException 租户 Key 数量超过上限
     */
    @Transactional
    public CreateApiKeyResult createApiKey(String merchantId, String scopes,
                                            String description, LocalDateTime expireAt) {
        // 检查 Key 数量上限
        long currentCount = apiKeyRepository.countByMerchantId(merchantId);
        if (currentCount >= maxKeysPerMerchant) {
            throw new IllegalStateException(
                    "API Key 数量已达上限 (" + maxKeysPerMerchant + ")，请先撤销或轮换旧 Key");
        }

        String keyId = generateKeyId();
        String plainSecret = generateKeySecret();
        String hashedSecret = hashSecret(plainSecret);

        ApiKey apiKey = new ApiKey();
        apiKey.setKeyId(keyId);
        apiKey.setKeySecret(hashedSecret);
        apiKey.setMerchantId(merchantId);
        apiKey.setScopes(scopes);
        apiKey.setStatus(ApiKeyStatus.ACTIVE);
        apiKey.setExpireAt(expireAt);
        apiKey.setDescription(description);

        apiKeyRepository.save(apiKey);
        log.info("创建 API Key: keyId={}, merchantId={}, scopes={}", keyId, merchantId, scopes);

        return new CreateApiKeyResult(apiKey, plainSecret);
    }

    /**
     * 轮换 API Key — 创建新 Key 替代旧 Key。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>查找旧 Key（必须存在且为 ACTIVE 状态）</li>
     *   <li>创建新 Key，继承旧 Key 的 scopes/description/expireAt，rotatedFromId 指向旧 Key</li>
     *   <li>旧 Key 标记为 ROTATED</li>
     * </ol>
     *
     * @param keyId 旧 Key 的公开标识
     * @return 新 Key 的创建结果（含明文 keySecret）
     * @throws IllegalArgumentException 旧 Key 不存在
     * @throws IllegalStateException    旧 Key 非 ACTIVE 状态
     */
    @Transactional
    public CreateApiKeyResult rotateApiKey(String keyId) {
        ApiKey oldKey = apiKeyRepository.findByKeyId(keyId)
                .orElseThrow(() -> new IllegalArgumentException("API Key 不存在: " + keyId));

        if (oldKey.getStatus() != ApiKeyStatus.ACTIVE) {
            throw new IllegalStateException(
                    "仅 ACTIVE 状态的 Key 可轮换，当前状态: " + oldKey.getStatus());
        }

        // 创建新 Key，继承旧 Key 的配置
        String newKeyId = generateKeyId();
        String plainSecret = generateKeySecret();
        String hashedSecret = hashSecret(plainSecret);

        ApiKey newKey = new ApiKey();
        newKey.setKeyId(newKeyId);
        newKey.setKeySecret(hashedSecret);
        newKey.setMerchantId(oldKey.getMerchantId());
        newKey.setScopes(oldKey.getScopes());
        newKey.setStatus(ApiKeyStatus.ACTIVE);
        newKey.setExpireAt(oldKey.getExpireAt());
        newKey.setRotatedFromId(oldKey.getKeyId());
        newKey.setDescription(oldKey.getDescription());

        // 旧 Key 标记 ROTATED
        oldKey.setStatus(ApiKeyStatus.ROTATED);

        apiKeyRepository.save(oldKey);
        apiKeyRepository.save(newKey);

        log.info("轮换 API Key: 旧 keyId={} → 新 keyId={}, merchantId={}",
                keyId, newKeyId, oldKey.getMerchantId());

        return new CreateApiKeyResult(newKey, plainSecret);
    }

    /**
     * 撤销 API Key — 标记 REVOKED，记录撤销原因与时间。
     *
     * @param keyId  Key 的公开标识
     * @param reason 撤销原因（可空）
     * @throws IllegalArgumentException Key 不存在
     * @throws IllegalStateException    Key 已非 ACTIVE 状态
     */
    @Transactional
    public ApiKey revokeApiKey(String keyId, String reason) {
        ApiKey apiKey = apiKeyRepository.findByKeyId(keyId)
                .orElseThrow(() -> new IllegalArgumentException("API Key 不存在: " + keyId));

        if (apiKey.getStatus() != ApiKeyStatus.ACTIVE) {
            throw new IllegalStateException(
                    "仅 ACTIVE 状态的 Key 可撤销，当前状态: " + apiKey.getStatus());
        }

        apiKey.setStatus(ApiKeyStatus.REVOKED);
        apiKey.setRevokedAt(LocalDateTime.now());
        apiKey.setRevokedReason(reason);

        apiKeyRepository.save(apiKey);
        log.info("撤销 API Key: keyId={}, reason={}", keyId, reason);

        return apiKey;
    }

    /**
     * 验证 API Key — 检查状态、过期时间与密钥哈希。
     *
     * <p>验证条件（全部满足才通过）：</p>
     * <ol>
     *   <li>Key 存在</li>
     *   <li>状态为 ACTIVE</li>
     *   <li>未过期（expireAt 为 null 或在当前时间之后）</li>
     *   <li>密钥哈希匹配（SHA-256(plainSecret) == stored hash）</li>
     * </ol>
     *
     * @param keyId      Key 的公开标识
     * @param keySecret  明文密钥（来自请求头）
     * @return 验证通过返回 Key 实体，否则返回 empty
     */
    @Transactional
    public java.util.Optional<ApiKey> validateApiKey(String keyId, String keySecret) {
        java.util.Optional<ApiKey> opt = apiKeyRepository.findByKeyId(keyId);
        if (opt.isEmpty()) {
            return java.util.Optional.empty();
        }

        ApiKey apiKey = opt.get();

        // 状态必须为 ACTIVE
        if (apiKey.getStatus() != ApiKeyStatus.ACTIVE) {
            log.debug("API Key 验证失败（状态非 ACTIVE）: keyId={}, status={}", keyId, apiKey.getStatus());
            return java.util.Optional.empty();
        }

        // 过期检查
        if (apiKey.getExpireAt() != null && apiKey.getExpireAt().isBefore(LocalDateTime.now())) {
            // 自动标记为 EXPIRED
            apiKey.setStatus(ApiKeyStatus.EXPIRED);
            apiKeyRepository.save(apiKey);
            log.debug("API Key 验证失败（已过期）: keyId={}, expireAt={}", keyId, apiKey.getExpireAt());
            return java.util.Optional.empty();
        }

        // 密钥哈希匹配
        String hashedInput = hashSecret(keySecret);
        if (!apiKey.getKeySecret().equals(hashedInput)) {
            log.debug("API Key 验证失败（密钥不匹配）: keyId={}", keyId);
            return java.util.Optional.empty();
        }

        return java.util.Optional.of(apiKey);
    }

    /**
     * 列出租户的所有 API Key（按创建时间降序）。
     *
     * @param merchantId 商户 ID
     * @return Key 列表
     */
    @Transactional(readOnly = true)
    public List<ApiKey> listApiKeys(String merchantId) {
        return apiKeyRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId);
    }

    /**
     * 更新 Key 的最后使用时间。
     *
     * @param keyId Key 的公开标识
     */
    @Transactional
    public void updateLastUsed(String keyId) {
        apiKeyRepository.findByKeyId(keyId).ifPresent(apiKey -> {
            apiKey.setLastUsedAt(LocalDateTime.now());
            apiKeyRepository.save(apiKey);
        });
    }

    // --- 内部方法 ---

    /**
     * 生成 keyId："ak_live_" + UUID 前16位。
     */
    private String generateKeyId() {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        return KEY_ID_PREFIX + uuid.substring(0, 16);
    }

    /**
     * 生成明文密钥：SecureRandom 32字节 → Base64 编码。
     */
    private String generateKeySecret() {
        byte[] bytes = new byte[secretLengthBytes];
        secureRandom.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * 计算 SHA-256 哈希（Hex 编码）。
     */
    private String hashSecret(String plainSecret) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hashBytes = digest.digest(plainSecret.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // --- DTO ---

    /**
     * 创建 API Key 的返回结果 — 包含 Key 实体和明文密钥。
     *
     * <p>明文 keySecret 仅在创建时返回一次，后续无法再获取。
     * 调用方应立即安全存储明文密钥。</p>
     */
    public static class CreateApiKeyResult {

        private final ApiKey apiKey;
        private final String plainSecret;

        public CreateApiKeyResult(ApiKey apiKey, String plainSecret) {
            this.apiKey = apiKey;
            this.plainSecret = plainSecret;
        }

        public ApiKey getApiKey() { return apiKey; }

        /** 明文密钥 — 仅创建时返回，后续无法恢复。 */
        public String getPlainSecret() { return plainSecret; }

        /**
         * 转换为响应 Map（用于 Controller 返回 JSON）。
         * 包含 Key 的公开字段和明文密钥。
         */
        public Map<String, Object> toResponseMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("keyId", apiKey.getKeyId());
            map.put("keySecret", plainSecret);
            map.put("merchantId", apiKey.getMerchantId());
            map.put("scopes", apiKey.getScopes());
            map.put("status", apiKey.getStatus().name());
            map.put("expireAt", apiKey.getExpireAt());
            map.put("description", apiKey.getDescription());
            map.put("createdAt", apiKey.getCreatedAt());
            map.put("rotatedFromId", apiKey.getRotatedFromId());
            return map;
        }
    }
}