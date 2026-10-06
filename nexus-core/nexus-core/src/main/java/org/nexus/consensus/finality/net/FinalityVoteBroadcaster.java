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
 * 最终性投票广播器（ADR-030 M_net）。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>把 {@link Vote} 序列化为可被 P2P 消费的载荷（{@link FinalityVoteCodec}）</li>
 *   <li>节点内分发：事件总线（{@link FinalityVoteBroadcastEvent}）+ {@link VoteListener}</li>
 *   <li>跨节点分发：{@code sendOverP2P()} 经 {@code PeerServer.broadcast} 推送</li>
 *   <li>接收端（{@code onVoteReceived(byte[])}）反序列化并提交 {@link FinalityGadget}</li>
 * </ul>
 *
 * <p><b>更正（2026-10-07 复核）</b>：本类原注释写"当前实现使用进程内事件总线作为中继，
 * 真正接入 P2P gossip 需等待 proto 工具链（protoc 3.22.2）就位，见
 * {@code docs/adr/ADR-031-finality-p2p-integration.md}"——两处都已过期：
 * <ol>
 *   <li>P2P 投递<b>两侧都已接线</b>：发送侧 {@code broadcast() → sendOverP2P()}
 *       把投票封装成 {@code Transactions(TransactionType.VOTE)} 复用交易通道
 *       （见本类 {@code sendOverP2P()}）；接收侧 {@code SyncManager.onTransactions()}
 *       以 {@link FinalityVoteP2PCodec#isVotePayload(byte[])} 为唯一分流点后回调
 *       {@code onVoteReceived(byte[])}。"等 protoc 就位"这个前提本身也不成立：
 *       生成物 {@code NexusChainOuterClass.java} 已直接入库
 *       （{@code src/main/java/org/nexus/p2p/}），构建侧只有 {@code protobuf-java} 运行时依赖
 *       （{@code nexus-core/nexus-core/build.gradle:204}），**没有 protobuf 代码生成插件**，
 *       protoc 从不在构建路径上。</li>
 *   <li>被引用的那份 ADR 文件名不存在；实际记录在同一编号下：
 *       {@code docs/adr/ADR-031-nexfinality-engineering-decisions.md}
 *       （其中"复用 TRANSACTIONS 通道、靠魔数区分投票"即本类的语义代价）。</li>
 * </ol>
 *
 * <p><b>仍然属实的那半句，别读错</b>：{@code peerServer} 为空时 {@code sendOverP2P()} 静默跳过，
 * 只剩进程内事件总线——而两个测试（{@code FinalityVoteBroadcasterTest}、
 * {@code FinalityEndToEndIntegrationTest}）用的都是不带 {@code PeerServer} 的两参构造器，
 * 全仓也没有任何测试触达 {@code onTransactions}/{@code sendOverP2P}。
 * 也就是说：<b>跨节点真实投递目前无测试覆盖</b>，"已接线"不等于"已验证"。</p>
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
