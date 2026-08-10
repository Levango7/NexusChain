package org.nexus.signing.mpc.router;

import org.nexus.signing.mpc.MpcProtocolException;
import org.nexus.signing.mpc.transport.MpcMessage;
import org.nexus.signing.mpc.transport.MpcTransport;
import org.nexus.signing.mpc.wal.WriteAheadLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MPC 消息路由器：广播 / 点对点 + 去重 + WAL 持久化。
 *
 * <p>该组件是上层协议逻辑与传输层之间的中间层，提供两种路由模式：</p>
 * <ul>
 *   <li>{@link #broadcast(MpcMessage)}：将消息发送给会话内所有其他参与者。
 *       广播消息的 {@code toParticipantId} 设为 {@code null}，由传输层
 *       负责复制到所有目标。</li>
 *   <li>{@link #sendTo(MpcMessage, String)}：将消息发送给指定参与者。
 *       返回一个新的 {@link MpcMessage} 副本，{@code toParticipantId} 设为目标。</li>
 * </ul>
 *
 * <p><b>处理流水</b>（出站）：</p>
 * <ol>
 *   <li>WAL 追加（未提交）—— 保证崩溃后可回放。</li>
 *   <li>传输层发送。</li>
 *   <li>WAL 标记已提交 —— 发送成功。</li>
 * </ol>
 *
 * <p><b>处理流水</b>（入站）：</p>
 * <ol>
 *   <li>去重检查 —— 重复消息直接丢弃。</li>
 *   <li>返回给上层处理。</li>
 * </ol>
 *
 * <p>注意：广播时 WAL 只追加一条记录（消息 ID 唯一），由传输层负责多目标复制；
 * 若某目标失败需重发，重发消息的 ID 不变，对端去重器会丢弃重复——这是
 * at-least-once + 去重 = exactly-once 的关键。</p>
 */
@Component
public class MessageRouter {

    private static final Logger log = LoggerFactory.getLogger(MessageRouter.class);

    private final MpcTransport transport;
    private final WriteAheadLog wal;
    private final MessageDeduplicator deduplicator;

    /**
     * 构造消息路由器。
     *
     * @param transport     传输层
     * @param wal           WAL（可 null，禁用持久化）
     * @param deduplicator  去重器（可 null，禁用去重）
     */
    public MessageRouter(MpcTransport transport,
                         @Autowired(required = false) WriteAheadLog wal,
                         @Autowired(required = false) MessageDeduplicator deduplicator) {
        this.transport = transport;
        this.wal = wal;
        this.deduplicator = deduplicator;
    }

    /**
     * 广播一条消息给会话内所有其他参与者。
     *
     * @param message 待广播消息（{@code toParticipantId} 应为 null）
     */
    public void broadcast(MpcMessage message) {
        if (!message.isBroadcast()) {
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.ILLEGAL_STATE,
                    "broadcast called with non-broadcast message: " + message.getMessageId());
        }
        routeOutbound(message);
        log.debug("Broadcast routed: session={}, round={}, msg={}",
                message.getSessionId(), message.getRound(), message.getMessageId());
    }

    /**
     * 发送一条点对点消息给指定参与者。
     *
     * @param message       原始消息（{@code toParticipantId} 可为 null，会被覆盖）
     * @param toParticipantId 目标参与者 ID
     */
    public void sendTo(MpcMessage message, String toParticipantId) {
        MpcMessage targeted = MpcMessage.create(
                message.getSessionId(),
                message.getRound(),
                message.getType(),
                message.getFromParticipantId(),
                toParticipantId,
                message.getPayloadHex());
        routeOutbound(targeted);
        log.debug("sendTo routed: {} -> {}", message.getMessageId(), toParticipantId);
    }

    /**
     * 入站处理：去重 + 返回。
     *
     * @param message 接收到的消息
     * @return {@code true} iff 该消息应被上层处理（非重复）
     */
    public boolean receive(MpcMessage message) {
        if (deduplicator != null) {
            return deduplicator.checkAndRecord(message);
        }
        return true;
    }

    /**
     * 从 WAL 回放指定会话的未提交消息。
     *
     * @param sessionId 会话 ID
     * @return 回放的消息数
     */
    public int replayFromWal(String sessionId) {
        if (wal == null) return 0;
        List<byte[]> pending = wal.recover(sessionId);
        int replayed = 0;
        for (byte[] bytes : pending) {
            try {
                MpcMessage msg = MpcMessage.fromByteArray(bytes);
                transport.send(msg);
                wal.commit(sessionId, msg.getMessageId());
                replayed++;
            } catch (Exception e) {
                log.warn("WAL replay failed for session {}: {}", sessionId, e.getMessage());
            }
        }
        log.info("WAL replay: session={}, replayed={}", sessionId, replayed);
        return replayed;
    }

    private void routeOutbound(MpcMessage message) {
        // 1. WAL 追加
        if (wal != null) {
            wal.append(message.getSessionId(), message.getMessageId(), message.toByteArray());
        }
        // 2. 传输层发送
        try {
            transport.send(message);
        } catch (Exception e) {
            log.warn("Outbound send failed (will be replayed from WAL on recovery): {}",
                    e.getMessage());
            throw e;
        }
        // 3. WAL 提交
        if (wal != null) {
            wal.commit(message.getSessionId(), message.getMessageId());
        }
    }
}