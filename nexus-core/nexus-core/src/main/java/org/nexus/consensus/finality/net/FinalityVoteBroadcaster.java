package org.nexus.consensus.finality.net;

import com.google.protobuf.ByteString;
import org.nexus.consensus.finality.FinalityGadget;
import org.nexus.consensus.finality.Vote;
import org.nexus.p2p.NexusChainOuterClass;
import org.nexus.p2p.PeerServer;
import org.nexus.sync.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 最终性投票广播器（ADR-030 M_net 占位实现）。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>把 {@link Vote} 序列化为可被 P2P 消费的载荷（{@link FinalityVoteCodec}）</li>
 *   <li>通过事件总线广播（{@link FinalityVoteBroadcastEvent}），与现有 gRPC/P2P 解耦</li>
 *   <li>接收端（{@linkplain #onVoteReceived(Vote) 对端投递接口}）反序列化并提交 {@link FinalityGadget}</li>
 * </ul>
 *
 * <p><b>注意</b>：当前实现使用进程内事件总线作为中继（单节点/测试用），
 * 真正接入 P2P gossip 需等待 proto 工具链（protoc 3.22.2）就位，
 * 见 {@code docs/adr/ADR-031-finality-p2p-integration.md}（下一步 ADR）。
 * 语义已冻结：载荷格式一致，后续仅替换投递机制。</p>
 */
@Component
@ConditionalOnProperty(name = "nexus.consensus.mode", havingValue = "pos")
public class FinalityVoteBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(FinalityVoteBroadcaster.class);

    private final FinalityGadget gadget;
    private final ApplicationEventPublisher eventPublisher;
    private final PeerServer peerServer;  // P2P 发送侧（可选；单进程/测试为 null）
    private final List<VoteListener> externalListeners = new CopyOnWriteArrayList<>();

    public FinalityVoteBroadcaster(FinalityGadget gadget, ApplicationEventPublisher eventPublisher) {
        this(gadget, eventPublisher, null);
    }

    @Autowired
    public FinalityVoteBroadcaster(FinalityGadget gadget,
                                   ApplicationEventPublisher eventPublisher,
                                   @Autowired(required = false) PeerServer peerServer) {
        this.gadget = Objects.requireNonNull(gadget, "gadget must not be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
        this.peerServer = peerServer;
    }

    /**
     * 广播投票（本节点产生后向 P2P 网络推送）。
     */
    public void broadcast(Vote vote) {
        byte[] payload = FinalityVoteCodec.encode(vote);
        log.debug("Broadcasting finality vote: epoch={}, validator={}, payloadBytes={}",
                vote.getEpoch(), vote.getValidatorAddress(), payload.length);
        // 事件发布（节点内订阅者可收到）
        eventPublisher.publishEvent(new FinalityVoteBroadcastEvent(vote, payload));
        // P2P 发送侧：构造 TRANSACTIONS 消息（VOTE 交易 + 载荷）广播给对端
        sendOverP2P(vote, payload);
        // 外部监听者
        for (VoteListener l : externalListeners) {
            try {
                l.onOutgoingVote(vote, payload);
            } catch (RuntimeException e) {
                log.warn("Vote listener failed during broadcast: {}", e.getMessage());
            }
        }
    }

    /**
     * P2P 发送侧（跨节点汇聚关键件）：把投票封装为 {@code TransactionType.VOTE}
     * 交易 + payload 载荷，经 {@link PeerServer#broadcast} 广播给对端节点。
     */
    private void sendOverP2P(Vote vote, byte[] payload) {
        if (peerServer == null) {
            return;  // 单进程/测试：无 P2P，静默跳过
        }
        try {
            NexusChainOuterClass.Transaction tx = NexusChainOuterClass.Transaction.newBuilder()
                    .setTransactionType(NexusChainOuterClass.TransactionType.VOTE)
                    .setPayload(ByteString.copyFrom(payload))
                    .build();
            NexusChainOuterClass.Transactions msg = NexusChainOuterClass.Transactions.newBuilder()
                    .addTransactions(tx)
                    .build();
            peerServer.broadcast(msg);
            log.info("Finality vote broadcast over P2P: epoch={}, validator={}",
                    vote.getEpoch(), vote.getValidatorAddress());
        } catch (RuntimeException e) {
            log.warn("P2P vote broadcast failed: {}", e.getMessage());
        }
    }

    /**
     * 处理来自网络的投票（接收端）。
     * 反序列化后投递至 {@link FinalityGadget#submitVote(Vote)}。
     */
    public void onVoteReceived(byte[] payload) {
        Vote vote = FinalityVoteCodec.decode(payload);
        if (vote == null) {
            log.warn("Received malformed finality vote payload (dropped)");
            return;
        }
        log.debug("Received finality vote: epoch={}, validator={}",
                vote.getEpoch(), vote.getValidatorAddress());
        gadget.submitVote(vote);
    }

    /**
     * 注册监听器（用于未来挂接 P2P 真实通道）。
     */
    public void addListener(VoteListener listener) {
        externalListeners.add(Objects.requireNonNull(listener));
    }

    /**
     * 投票广播事件（进程内事件总线信封）。
     */
    public static final class FinalityVoteBroadcastEvent extends ApplicationEvent {
        private final Vote vote;
        private final byte[] payload;

        public FinalityVoteBroadcastEvent(Vote vote, byte[] payload) {
            super(vote);
            this.vote = vote;
            this.payload = payload;
        }

        public Vote getVote() { return vote; }
        public byte[] getPayload() { return payload; }
    }

    /**
     * 外部投票监听器（用于未来桥接真实 P2P 通道）。
     */
    public interface VoteListener {
        void onOutgoingVote(Vote vote, byte[] payload);
        default void onIncomingVote(Vote vote) {}
    }
}
