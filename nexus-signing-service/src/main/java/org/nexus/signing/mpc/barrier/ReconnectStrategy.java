package org.nexus.signing.mpc.barrier;

import org.nexus.signing.mpc.MpcParticipant;
import org.nexus.signing.mpc.MpcProtocolException;
import org.nexus.signing.mpc.transport.MpcTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 重连策略：在传输层断开时按指数退避重试。
 *
 * <p>当 {@link MpcTransport} 连接断开或某轮次超时时，按以下策略重连：</p>
 * <ol>
 *   <li>等待 {@code initialBackoffMillis} 毫秒。</li>
 *   <li>尝试 {@link MpcTransport#connect(List)}。</li>
 *   <li>若失败，等待 {@code 2 * 上次等待}（上限 {@code maxBackoffMillis}）。</li>
 *   <li>重复直到成功或达到 {@code maxRetries} 次。</li>
 * </ol>
 *
 * <p>每次重连会触发 {@link HealthCheck#markReconnect(String)} 以更新健康状态。</p>
 */
public class ReconnectStrategy {

    private static final Logger log = LoggerFactory.getLogger(ReconnectStrategy.class);

    private final MpcTransport transport;
    private final HealthCheck healthCheck;
    private final long initialBackoffMillis;
    private final long maxBackoffMillis;
    private final int maxRetries;

    private final AtomicInteger attemptCount = new AtomicInteger(0);

    /**
     * 构造重连策略。
     *
     * @param transport             传输层
     * @param healthCheck           健康检查（可 null）
     * @param initialBackoffMillis  初始退避（毫秒）
     * @param maxBackoffMillis      最大退避（毫秒）
     * @param maxRetries            最大重试次数
     */
    public ReconnectStrategy(MpcTransport transport, HealthCheck healthCheck,
                            long initialBackoffMillis, long maxBackoffMillis, int maxRetries) {
        this.transport = transport;
        this.healthCheck = healthCheck;
        this.initialBackoffMillis = initialBackoffMillis;
        this.maxBackoffMillis = maxBackoffMillis;
        this.maxRetries = maxRetries;
    }

    /**
     * 尝试重连。
     *
     * @param participants 参与者列表
     * @return {@code true} iff 重连成功
     * @throws MpcProtocolException 若达到最大重试次数仍失败
     */
    public boolean reconnect(List<MpcParticipant> participants) {
        long backoff = initialBackoffMillis;
        for (int i = 1; i <= maxRetries; i++) {
            attemptCount.set(i);
            log.warn("Reconnect attempt {}/{} for {} participants (backoff={}ms)",
                    i, maxRetries, participants.size(), backoff);
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MpcProtocolException(
                        MpcProtocolException.Reason.TIMEOUT,
                        "reconnect interrupted", e);
            }
            try {
                transport.close();
                transport.connect(participants);
                if (healthCheck != null) {
                    for (MpcParticipant p : participants) {
                        healthCheck.markReconnect(p.getParticipantId());
                    }
                }
                log.info("Reconnect succeeded after {} attempts", i);
                return true;
            } catch (Exception e) {
                log.warn("Reconnect attempt {} failed: {}", i, e.getMessage());
                backoff = Math.min(backoff * 2, maxBackoffMillis);
            }
        }
        throw new MpcProtocolException(
                MpcProtocolException.Reason.QUORUM_NOT_REACHED,
                "reconnect failed after " + maxRetries + " attempts");
    }

    /**
     * @return 当前已尝试的重连次数
     */
    public int getAttemptCount() {
        return attemptCount.get();
    }
}