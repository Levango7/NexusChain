package org.nexus.wallet.signing.mpc.transport;

import org.nexus.wallet.signing.mpc.MpcParticipant;
import org.nexus.wallet.signing.mpc.MpcProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 进程内内存传输层实现（composite build 占位 / 单 JVM 测试用）。
 *
 * <p>所有参与者位于同一 JVM，消息通过 {@link ConcurrentLinkedQueue} 直接入队，
 * 不经过任何网络层。该实现：</p>
 * <ul>
 *   <li>线程安全：每个 (sessionId, round, toParticipantId) 邮箱独立队列与锁。</li>
 *   <li>支持点对点与广播：广播消息会复制到所有目标参与者邮箱。</li>
 *   <li>支持超时接收：使用 {@link Condition#await(long, TimeUnit)} 实现。</li>
 * </ul>
 *
 * <p>该类是 {@link MpcTransport} 的默认实现，生产环境应替换为
 * {@link GrpcMpcTransportStub} 或真实 gRPC 实现。</p>
 */
public class InMemoryMpcTransport implements MpcTransport {

    private static final Logger log = LoggerFactory.getLogger(InMemoryMpcTransport.class);

    /** 邮箱键：(sessionId, round, toParticipantId)。 */
    private final Map<String, Mailbox> mailboxes = new ConcurrentHashMap<>();

    /** 当前连接的参与者（按 ID 索引），用于广播。 */
    private final Map<String, MpcParticipant> connectedParticipants = new ConcurrentHashMap<>();

    private volatile boolean connected = false;

    @Override
    public void connect(List<MpcParticipant> participants) {
        if (participants == null || participants.isEmpty()) {
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.QUORUM_NOT_REACHED,
                    "no participants to connect");
        }
        connectedParticipants.clear();
        for (MpcParticipant p : participants) {
            connectedParticipants.put(p.getParticipantId(), p);
        }
        connected = true;
        log.info("InMemoryMpcTransport connected: {} participants", participants.size());
    }

    @Override
    public void send(MpcMessage message) {
        ensureConnected();
        if (message.isBroadcast()) {
            // 广播：复制到所有其他参与者邮箱
            for (String toId : connectedParticipants.keySet()) {
                if (!toId.equals(message.getFromParticipantId())) {
                    mailboxOf(message.getSessionId(), message.getRound(), toId).offer(message);
                }
            }
            log.debug("Broadcast message {} to {} recipients",
                    message.getMessageId(), connectedParticipants.size() - 1);
        } else {
            // 点对点
            if (!connectedParticipants.containsKey(message.getToParticipantId())) {
                throw new MpcProtocolException(
                        MpcProtocolException.Reason.QUORUM_NOT_REACHED,
                        "unknown participant: " + message.getToParticipantId());
            }
            mailboxOf(message.getSessionId(), message.getRound(),
                    message.getToParticipantId()).offer(message);
            log.debug("Sent message {} to {}", message.getMessageId(), message.getToParticipantId());
        }
    }

    @Override
    public MpcMessage receive(String sessionId, int round, String fromParticipantId, long timeoutMillis) {
        ensureConnected();
        // 接收方 = 当前节点；这里通过线程局部推断接收者不现实，
        // 改为遍历所有邮箱查找匹配 fromId 的消息（适用于测试场景）。
        // 真实场景下 GrpcMpcTransportStub 会有明确的本地 participantId。
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (true) {
            for (Mailbox mb : mailboxes.values()) {
                MpcMessage m = mb.peekMatching(sessionId, round, fromParticipantId);
                if (m != null) return m;
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                throw new MpcProtocolException(
                        MpcProtocolException.Reason.TIMEOUT,
                        "receive timeout: session=" + sessionId + ", round=" + round
                                + ", from=" + fromParticipantId);
            }
            try {
                Thread.sleep(Math.min(10, remaining));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MpcProtocolException(
                        MpcProtocolException.Reason.TIMEOUT,
                        "receive interrupted", e);
            }
        }
    }

    @Override
    public void close() {
        mailboxes.clear();
        connectedParticipants.clear();
        connected = false;
        log.info("InMemoryMpcTransport closed");
    }

    @Override
    public boolean isConnected() { return connected; }

    private void ensureConnected() {
        if (!connected) {
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.ILLEGAL_STATE,
                    "transport not connected");
        }
    }

    private Mailbox mailboxOf(String sessionId, int round, String participantId) {
        return mailboxes.computeIfAbsent(
                sessionId + "|" + round + "|" + participantId, k -> new Mailbox());
    }

    /**
     * 单个参与者在一个轮次的邮箱。
     */
    private static final class Mailbox {
        private final ConcurrentLinkedQueue<MpcMessage> queue = new ConcurrentLinkedQueue<>();
        private final Lock lock = new ReentrantLock();
        private final Condition condition = lock.newCondition();

        void offer(MpcMessage m) {
            queue.offer(m);
            lock.lock();
            try {
                condition.signalAll();
            } finally {
                lock.unlock();
            }
        }

        MpcMessage peekMatching(String sessionId, int round, String fromId) {
            for (MpcMessage m : queue) {
                if (m.getSessionId().equals(sessionId)
                        && m.getRound() == round
                        && m.getFromParticipantId().equals(fromId)) {
                    if (queue.remove(m)) return m;
                }
            }
            return null;
        }
    }
}