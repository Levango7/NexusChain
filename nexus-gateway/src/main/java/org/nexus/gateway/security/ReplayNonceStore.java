package org.nexus.gateway.security;

/**
 * 防重放 nonce 存储（短期项 #4b，2026-09-17）。
 *
 * <p><b>动机</b>：原实现为进程内 {@code ConcurrentHashMap}——Helm 生产部署
 * gateway {@code minReplicas: 2}，多副本各自维护 nonce 表，5 分钟窗口内
 * 同一 nonce 打到不同副本均可通过（跨 Pod 重放）。</p>
 *
 * <p>实现分层：内存（dev/sandbox/test 单实例）与 Redis
 * （prod，SET NX + TTL 共享存储，多副本一致）。Redis 异常时
 * <b>fail-closed</b>（返回 false = 视为重放拒绝），不静默放行。</p>
 */
public interface ReplayNonceStore {

    /**
     * 登记一个 nonce。
     *
     * @param nonce     请求唯一数
     * @param ttlMillis 防重放窗口（毫秒），过期后可复用
     * @return {@code true} 若该 nonce 在窗口内首次出现（放行）；
     *         {@code false} 表示重复（重放）或存储不可用（fail-closed）
     */
    boolean register(String nonce, long ttlMillis);
}
