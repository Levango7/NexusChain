package org.nexus.gateway.tenant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2 回归测试（2026-09-21）。
 *
 * <p>修复前 {@code TenantApiKeyCache} 仅有 TTL、**无容量上限**：TTL 为读时惰性过期，
 * 写入后若再无请求命中该 key，条目不会被清除；负缓存同样占位。
 * 长期运行下条目数随「出现过的不同 API Key 数量」单调增长 → 内存无界膨胀。</p>
 *
 * <p>本类使用负缓存（{@code Optional.empty()}）驱动，无需构造 Tenant 实例。</p>
 */
class TenantApiKeyCacheCapacityTest {

    @Test
    @DisplayName("写入超过上限时条目数被限制在上限内（不无界增长）")
    void entriesAreBoundedByMaxSize() {
        TenantApiKeyCache cache = new TenantApiKeyCache();

        int overflow = TenantApiKeyCache.MAX_ENTRIES + 500;
        for (int i = 0; i < overflow; i++) {
            cache.put("key-" + i, Optional.empty());
        }

        assertThat(cache.size())
                .as("缓存条目数必须被上限约束，实际=%d，上限=%d",
                        cache.size(), TenantApiKeyCache.MAX_ENTRIES)
                .isLessThanOrEqualTo(TenantApiKeyCache.MAX_ENTRIES);
    }

    @Test
    @DisplayName("未达上限时不做淘汰，全部条目保留")
    void belowCapacityKeepsAllEntries() {
        TenantApiKeyCache cache = new TenantApiKeyCache();

        for (int i = 0; i < 100; i++) {
            cache.put("key-" + i, Optional.empty());
        }

        assertThat(cache.size()).isEqualTo(100);
    }

    @Test
    @DisplayName("淘汰优先清理已过期条目")
    void expiredEntriesAreEvictedFirst() {
        // TTL 极小 → 写入后即视为过期
        TenantApiKeyCache cache = new TenantApiKeyCache(1L);

        // 先填满到上限
        for (int i = 0; i < TenantApiKeyCache.MAX_ENTRIES; i++) {
            cache.put("old-" + i, Optional.empty());
        }
        assertThat(cache.size()).isEqualTo(TenantApiKeyCache.MAX_ENTRIES);

        // 等待全部过期后触发一次写入
        try {
            Thread.sleep(10L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        cache.put("trigger", Optional.empty());

        // 过期条目应被批量清除，而非只淘汰最旧的一条
        assertThat(cache.size())
                .as("过期条目应被清理，实际=%d", cache.size())
                .isLessThan(TenantApiKeyCache.MAX_ENTRIES);
    }

    @Test
    @DisplayName("空 key 不写入缓存")
    void blankKeyIsIgnored() {
        TenantApiKeyCache cache = new TenantApiKeyCache();

        cache.put(null, Optional.empty());
        cache.put("", Optional.empty());

        assertThat(cache.size()).isZero();
    }
}
