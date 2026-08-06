package org.nexus.wallet.signing.mpc.barrier;

import org.nexus.wallet.signing.mpc.MpcParticipant;
import org.nexus.wallet.signing.mpc.transport.MpcMessage;
import org.nexus.wallet.signing.mpc.transport.MpcTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 健康检查：心跳机制 + 参与者可达性跟踪。
 *
 * <p>定期（{@code heartbeatIntervalSeconds}）向所有参与者发送心跳控制消息，
 * 并跟踪每个参与者的最后心跳时间。若超过 {@code heartbeatTimeoutSeconds}
 * 未收到响应，标记该参与者不可达。</p>
 *
 * <p><b>使用方式</b>：</p>
 * <pre>
 *   HealthCheck hc = new HealthCheck(transport, participants, 5, 15);
 *   hc.start();   // 启动心跳发送
 *   // 接收消息时：
 *   hc.recordHeartbeat(senderId);
 *   // 检查可达性：
 *   boolean alive = hc.isAlive(participantId);
 *   hc.stop();    // 停止
 * </pre>
 */
public class HealthCheck {

    private static final Logger log = LoggerFactory.getLogger(HealthCheck.class);

    private final MpcTransport transport;
    private final long heartbeatIntervalSeconds;
    private final long heartbeatTimeoutSeconds;

    /** participantId -> 最后心跳时间（毫秒）。 */
    private final Map<String, Long> lastHeartbeat = new ConcurrentHashMap<>();

    /** participantId -> 心跳序号（每次发送递增）。 */
    private final Map<String, AtomicLong> heartbeatSeq = new ConcurrentHashMap<>();

    /** 参与者列表（用于发送心跳）。 */
    private final List<MpcParticipant> participants;

    /** 本地参与者 ID（用于发送心跳源）。 */
    private final String localParticipantId;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mpc-healthcheck");
                t.setDaemon(true);
                return t;
            });
    private ScheduledFuture<?> heartbeatTask;

    /**
     * 构造健康检查器。
     *
     * @param transport                  传输层
     * @param participants               参与者列表
     * @param localParticipantId         本地参与者 ID
     * @param heartbeatIntervalSeconds   心跳间隔（秒）
     * @param heartbeatTimeoutSeconds    心跳超时（秒）
     */
    public HealthCheck(MpcTransport transport, List<MpcParticipant> participants,
                       String localParticipantId,
                       long heartbeatIntervalSeconds, long heartbeatTimeoutSeconds) {
        this.transport = transport;
        this.participants = participants;
        this.localParticipantId = localParticipantId;
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
        this.heartbeatTimeoutSeconds = heartbeatTimeoutSeconds;
        for (MpcParticipant p : participants) {
            lastHeartbeat.put(p.getParticipantId(), Instant.now().toEpochMilli());
            heartbeatSeq.put(p.getParticipantId(), new AtomicLong(0));
        }
    }

    /**
     * 启动心跳发送任务。
     */
    public void start() {
        if (heartbeatTask != null) return;
        heartbeatTask = scheduler.scheduleAtFixedRate(
                this::sendHeartbeats,
                heartbeatIntervalSeconds,
                heartbeatIntervalSeconds,
                TimeUnit.SECONDS);
        log.info("HealthCheck started: interval={}s, timeout={}s, participants={}",
                heartbeatIntervalSeconds, heartbeatTimeoutSeconds, participants.size());
    }

    /**
     * 停止心跳发送。
     */
    public void stop() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
        }
        scheduler.shutdownNow();
        log.info("HealthCheck stopped");
    }

    /**
     * 记入一次接收到的消息（更新最后心跳时间）。
     *
     * @param participantId 发送者 ID
     */
    public void recordHeartbeat(String participantId) {
        lastHeartbeat.put(participantId, Instant.now().toEpochMilli());
    }

    /**
     * 标记参与者重连（重置其心跳时间）。
     *
     * @param participantId 参与者 ID
     */
    public void markReconnect(String participantId) {
        lastHeartbeat.put(participantId, Instant.now().toEpochMilli());
        log.info("HealthCheck: participant {} reconnected", participantId);
    }

    /**
     * 判断参与者是否存活（最近心跳在超时窗口内）。
     *
     * @param participantId 参与者 ID
     * @return {@code true} iff 存活
     */
    public boolean isAlive(String participantId) {
        Long last = lastHeartbeat.get(participantId);
        if (last == null) return false;
        return (Instant.now().toEpochMilli() - last) < heartbeatTimeoutSeconds * 1000L;
    }

    /**
     * @return 当前存活的参与者数
     */
    public long getAliveCount() {
        return participants.stream()
                .map(MpcParticipant::getParticipantId)
                .filter(this::isAlive)
                .count();
    }

    private void sendHeartbeats() {
        try {
            for (MpcParticipant p : participants) {
                if (p.getParticipantId().equals(localParticipantId)) continue;
                long seq = heartbeatSeq.get(p.getParticipantId()).incrementAndGet();
                MpcMessage heartbeat = MpcMessage.create(
                        "healthcheck", 0, MpcMessage.Type.CONTROL,
                        localParticipantId, p.getParticipantId(),
                        "HEARTBEAT|" + seq);
                transport.send(heartbeat);
            }
        } catch (Exception e) {
            log.warn("Heartbeat send failed: {}", e.getMessage());
        }
    }
}