package org.nexus.gateway.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * 默认租户管理服务实现（P4-T6 多租户改造）。
 *
 * <p>所有写操作在事务内执行；状态流转校验租户当前状态，非法流转抛
 * {@link IllegalStateException}。API Key 验证仅放行 {@link TenantStatus#ACTIVE}
 * 租户，由 {@link TenantApiKeyInterceptor} 调用。</p>
 *
 * <p>性能优化（任务 #310）：{@link #validateApiKey} 添加 TTL 内存缓存，
 * 避免每次 API 请求都查询数据库。租户状态变更时主动失效缓存。</p>
 */
@Service
public class DefaultTenantService implements TenantService {

    private static final Logger log = LoggerFactory.getLogger(DefaultTenantService.class);

    private final TenantRepository tenantRepository;
    /** API Key 验证缓存：高频调用（每个 API 请求一次），TTL 5 分钟。 */
    private final TenantApiKeyCache apiKeyCache = new TenantApiKeyCache();

    public DefaultTenantService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @Override
    @Transactional
    public Tenant createTenant(Tenant tenant) {
        if (tenant.getTenantId() == null || tenant.getTenantId().isEmpty()) {
            tenant.setTenantId(UUID.randomUUID().toString().replace("-", ""));
        }
        if (tenant.getApiKey() == null || tenant.getApiKey().isEmpty()) {
            tenant.setApiKey(UUID.randomUUID().toString().replace("-", ""));
        }
        if (tenant.getApiSecret() == null || tenant.getApiSecret().isEmpty()) {
            tenant.setApiSecret(UUID.randomUUID().toString().replace("-", ""));
        }
        if (tenant.getStatus() == null) {
            tenant.setStatus(TenantStatus.ACTIVE);
        }
        if (tenant.getConfig() == null) {
            tenant.setConfig(new TenantConfig());
        }
        if (tenantRepository.existsByTenantId(tenant.getTenantId())) {
            throw new IllegalStateException("Tenant ID already exists: " + tenant.getTenantId());
        }
        if (tenantRepository.existsByApiKey(tenant.getApiKey())) {
            throw new IllegalStateException("API Key already exists");
        }
        Tenant saved = tenantRepository.save(tenant);
        log.info("Created tenant: tenantId={}, name={}", saved.getTenantId(), saved.getName());
        return saved;
    }

    @Override
    public Optional<Tenant> getTenant(String tenantId) {
        return tenantRepository.findByTenantId(tenantId);
    }

    @Override
    @Transactional
    public Tenant updateTenant(String tenantId, Tenant updated) {
        Tenant existing = tenantRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));
        if (updated.getName() != null) {
            existing.setName(updated.getName());
        }
        if (updated.getConfig() != null) {
            existing.setConfig(updated.getConfig());
        }
        if (updated.getStatus() != null) {
            existing.setStatus(updated.getStatus());
        }
        Tenant saved = tenantRepository.save(existing);
        // 状态变更可能影响 API Key 验证结果，主动失效缓存
        apiKeyCache.invalidate(saved.getApiKey());
        return saved;
    }

    @Override
    @Transactional
    public Tenant updateTenantConfig(String tenantId, TenantConfig config) {
        Tenant existing = tenantRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));
        existing.setConfig(config);
        return tenantRepository.save(existing);
    }

    @Override
    @Transactional
    public Tenant suspendTenant(String tenantId) {
        Tenant existing = tenantRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));
        if (existing.getStatus() != TenantStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Cannot suspend tenant in status " + existing.getStatus());
        }
        existing.setStatus(TenantStatus.SUSPENDED);
        Tenant saved = tenantRepository.save(existing);
        // 暂停后 API Key 验证应立即拒绝，主动失效缓存
        apiKeyCache.invalidate(saved.getApiKey());
        log.info("Suspended tenant: tenantId={}", tenantId);
        return saved;
    }

    @Override
    @Transactional
    public Tenant terminateTenant(String tenantId) {
        Tenant existing = tenantRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));
        if (existing.getStatus() == TenantStatus.TERMINATED) {
            throw new IllegalStateException("Tenant already terminated: " + tenantId);
        }
        existing.setStatus(TenantStatus.TERMINATED);
        Tenant saved = tenantRepository.save(existing);
        // 终止后 API Key 验证应立即拒绝，主动失效缓存
        apiKeyCache.invalidate(saved.getApiKey());
        log.info("Terminated tenant: tenantId={}", tenantId);
        return saved;
    }

    @Override
    @Transactional
    public Tenant activateTenant(String tenantId) {
        Tenant existing = tenantRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));
        if (existing.getStatus() != TenantStatus.SUSPENDED) {
            throw new IllegalStateException(
                    "Cannot activate tenant in status " + existing.getStatus());
        }
        existing.setStatus(TenantStatus.ACTIVE);
        Tenant saved = tenantRepository.save(existing);
        // 激活后 API Key 验证应立即通过，主动失效缓存
        apiKeyCache.invalidate(saved.getApiKey());
        log.info("Activated tenant: tenantId={}", tenantId);
        return saved;
    }

    @Override
    public Optional<Tenant> validateApiKey(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            return Optional.empty();
        }
        // 查缓存：命中直接返回（含负缓存），未命中回源
        Optional<Tenant> cached = apiKeyCache.get(apiKey);
        if (cached != null) {
            return cached;
        }
        // 回源查询
        Optional<Tenant> opt = tenantRepository.findByApiKey(apiKey);
        if (opt.isEmpty()) {
            // 负缓存：不存在的 apiKey 也缓存，避免恶意请求打穿
            apiKeyCache.put(apiKey, Optional.empty());
            return Optional.empty();
        }
        Tenant tenant = opt.get();
        if (tenant.getStatus() != TenantStatus.ACTIVE) {
            log.warn("API Key validation rejected: tenantId={} status={}",
                    tenant.getTenantId(), tenant.getStatus());
            // 缓存非 ACTIVE 状态的结果（下次快速拒绝）
            apiKeyCache.put(apiKey, Optional.empty());
            return Optional.empty();
        }
        // 缓存 ACTIVE 租户
        apiKeyCache.put(apiKey, opt);
        return opt;
    }
}