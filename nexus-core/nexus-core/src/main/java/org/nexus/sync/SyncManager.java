package org.nexus.sync;

import com.google.protobuf.ByteString;
import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.nexus.core.*;
import org.nexus.core.event.NewBlockMinedEvent;
import org.nexus.core.validate.BasicRule;
import org.nexus.core.validate.CheckPointRule;
import org.nexus.core.validate.Result;
import org.nexus.db.StateDB;
import org.nexus.p2p.*;
import org.nexus.p2p.entity.GetBlockQuery;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;


/**
 * @author sal 1564319846@qq.com
 * nexus protocol block synchronize manager
 */
@Component
public class SyncManager implements Plugin, ApplicationListener<NewBlockMinedEvent> {
    private PeerServer server;
    private static final int CACHE_SIZE = 64;
    private static final Logger logger = LoggerFactory.getLogger(SyncManager.class);

    private ConcurrentMap<String, Boolean> proposalCache;

    @Value("${p2p.max-blocks-per-transfer}")
    private int maxBlocksPerTransfer;

    @Autowired
    private Block genesis;

    @Autowired
    private OrphanBlocksManager orphanBlocksManager;

    @Autowired
    private PendingBlocksManager pendingBlocksManager;

    @Autowired
    private BasicRule rule;

    @Autowired
    private StateDB stateDB;

    @Value("${nexus.consensus.allow-fork}")
    private boolean allowFork;

    @Value("${nexus.consensus.blocks-per-era}")
    private int blocksPerEra;

    /**
     * 最终性投票广播器（ADR-030 M_net 挂接点）。
     * 可选注入：未装配时投票消息仅记录日志，不进入 FinalityGadget。
     * @Lazy 打破循环依赖（FinalityCoordinator→Broadcaster→PeerServer→SyncManager→Broadcaster）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private org.nexus.consensus.finality.net.FinalityVoteBroadcaster finalityVoteBroadcaster;

    /**
     * 验证人注册表（PLAN-001 步骤 2：跨节点验证人同步的落点）。
     * 收到 validator-set 广播消息时幂等注册对端验证人。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.nexus.consensus.pos.ValidatorRegistry validatorRegistry;

    /**
     * 验证人集合持久化（PLAN-001 步骤 5：广播收到的验证人写共享表）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private org.nexus.consensus.finality.persistence.ValidatorSetPersistence validatorSetPersistence;

    /** 已知对端最高区块高度（PLAN-002：本地出块抑制依据，volatile 跨线程可见）。 */
    private volatile long knownMaxPeerHeight = 0;

    /**
     * 已知对端最高高度。供 PosMiningScheduler 在出块前比较：
     * 若本节点落后于对端，应抑制本地出块以跟随更长链（避免分叉持续）。
     */
    public long getKnownMaxPeerHeight() {
        return knownMaxPeerHeight;
    }

    @Autowired
    private CheckPointRule checkPointRule;

    public SyncManager() {
        this.proposalCache = new ConcurrentLinkedHashMap.Builder<String, Boolean>().maximumWeightedCapacity(CACHE_SIZE).build();
    }

    @Override
    public void onMessage(Context context, PeerServer server) {
        switch (context.getPayload().getCode()) {
            case GET_STATUS:
                onGetStatus(context, server);
                return;
            case STATUS:
                onStatus(context, server);
                return;
            case GET_BLOCKS:
                onGetBlocks(context, server);
                return;
            case BLOCKS:
                onBlocks(context, server);
                return;
            case TRANSACTIONS:
                onTransactions(context, server);
                return;
            case PROPOSAL:
                onProposal(context, server);
        }
    }

    @Override
    public void onStart(PeerServer server) {
        this.server = server;
    }

    @Scheduled(fixedRate = 30 * 1000)
    public void getStatus() {
        if (server == null) {
            return;
        }
        // check checkpoint in db
        Result result = checkPointRule.validateDBCheckPoint();
        if (!result.isSuccess()) {
            logger.info("cannot try to fetch block, reason: " + result.getMessage());
            return;
        }
        List<Peer> ps = server.getPeers();
        if (ps == null || ps.size() == 0) {
            return;
        }
        int index = Math.abs(ThreadLocalRandom.current().nextInt()) % ps.size();

        server.dial(ps.get(index), NexusChainOuterClass.GetStatus.newBuilder().build());
        for (Block b : orphanBlocksManager.getInitials()) {
            long startHeight = b.nHeight - blocksPerEra * 2 + 1;
            if (startHeight <= 0) {
                startHeight = 1;
            }
            NexusChainOuterClass.GetBlocks getBlocks = NexusChainOuterClass.GetBlocks.newBuilder()
                    .setClipDirection(NexusChainOuterClass.ClipDirection.CLIP_INITIAL)
                    .setStartHeight(startHeight)
                    .setStopHeight(b.nHeight).build();
            logger.info("sync orphans: try to fetch block start from " + getBlocks.getStartHeight() + " stop at " + getBlocks.getStopHeight());
            server.dial(ps.get(index), getBlocks);
        }
    }

    private void onGetBlocks(Context context, PeerServer server) {
        NexusChainOuterClass.GetBlocks getBlocks = context.getPayload().getGetBlocks();
        GetBlockQuery query = new GetBlockQuery(getBlocks.getStartHeight(), getBlocks.getStopHeight()).clip(maxBlocksPerTransfer, getBlocks.getClipDirection() == NexusChainOuterClass.ClipDirection.CLIP_INITIAL);

        logger.info("get blocks received start height = " + query.start + " stop height = " + query.stop);
        List<Block> blocksToSend = stateDB.getBlocks(query.start, query.stop, maxBlocksPerTransfer, getBlocks.getClipDirectionValue() > 0);
        if (blocksToSend == null || blocksToSend.size() == 0) {
            return;
        }
        NexusChainOuterClass.Blocks resp = NexusChainOuterClass.Blocks.newBuilder().addAllBlocks(Utils.encodeBlocks(blocksToSend)).build();
        List<NexusChainOuterClass.Blocks> divided = Util.split(resp);
        if (divided.size() == 0){
            return;
        }
        context.response(divided.get(0));
        divided.subList(1, divided.size()).forEach(o -> server.dial(context.getPayload().getRemote(), o));
    }

    private void onBlocks(Context context, PeerServer server) {
        NexusChainOuterClass.Blocks blocksMessage = context.getPayload().getBlocks();
        receiveBlocks(Utils.parseBlocks(blocksMessage.getBlocksList()));
    }

    /**
     * 处理收到的单条交易（TRANSACTIONS code）。
     *
     * <p>NexFinality M_net：筛出 {@code transaction_type == VOTE} 且 payload 带投票魔数的交易，
     * 解码后注入 {@link org.nexus.consensus.finality.net.FinalityVoteBroadcaster}；
     * 其余交易交由原有交易流程（此处暂不接管既有管道）。</p>
     */
    private void onTransactions(Context context, PeerServer server) {
        if (finalityVoteBroadcaster == null) {
            return;
        }
        // 广播容器为 Transactions（复数），遍历其中 VOTE 类型交易
        NexusChainOuterClass.Transactions txs = context.getPayload().getTransactions();
        if (txs == null) {
            return;
        }
        for (NexusChainOuterClass.Transaction tx : txs.getTransactionsList()) {
            byte[] payload = tx.getPayload().toByteArray();
            // PLAN-001：验证人集合广播消息（魔数 0x56）优先分流
            if (org.nexus.consensus.finality.net.ValidatorSetCodec.isValidatorSetPayload(payload)) {
                handleValidatorSet(payload);
                continue;
            }
            if (tx.getTransactionType() != NexusChainOuterClass.TransactionType.VOTE) {
                continue;
            }
            if (org.nexus.consensus.finality.net.FinalityVoteP2PCodec.isVotePayload(payload)) {
                finalityVoteBroadcaster.onVoteReceived(payload);
            }
        }
    }

    /**
     * 处理验证人集合广播（PLAN-001 步骤 2）：幂等注册对端验证人。
     * 非活跃注册表未装配时跳过（单测/无共识节点）。
     */
    private void handleValidatorSet(byte[] payload) {
        org.nexus.consensus.finality.net.ValidatorSetCodec.ValidatorSetMessage msg =
                org.nexus.consensus.finality.net.ValidatorSetCodec.decode(payload);
        if (msg == null || msg.address() == null || msg.address().isEmpty()) {
            logger.warn("Malformed validator-set message (dropped)");
            return;
        }
        if (validatorRegistry == null) {
            return;
        }
        try {
            if (msg.isAdd()) {
                if (msg.publicKey() == null || msg.stakeAmount() == null) {
                    logger.warn("Validator-set add missing pubkey/stake (dropped): {}", msg.address());
                    return;
                }
                boolean ok = validatorRegistry.register(
                        msg.address(), msg.publicKey(),
                        new java.math.BigDecimal(msg.stakeAmount()), 0.1);
                // PLAN-001 步骤 5：广播收到的验证人也写共享表（落库重放）
                if (validatorSetPersistence != null) {
                    validatorSetPersistence.upsert(msg.address(), msg.publicKey(),
                            new java.math.BigDecimal(msg.stakeAmount()));
                }
                logger.info("Validator-set add: address={} registered={} (via P2P)", msg.address(), ok);
            } else {
                boolean ok = validatorRegistry.unregister(msg.address());
                if (validatorSetPersistence != null) {
                    validatorSetPersistence.remove(msg.address());
                }
                logger.info("Validator-set remove: address={} removed={} (via P2P)", msg.address(), ok);
            }
        } catch (RuntimeException e) {
            logger.error("Validator-set apply failed: address={}, error={}", msg.address(), e.getMessage());
        }
    }

    private void onProposal(Context context, PeerServer server) {
        if (!allowFork) {
            return;
        }
        NexusChainOuterClass.Proposal proposal = context.getPayload().getProposal();
        Block block = Utils.parseBlock(proposal.getBlock());
        if (proposalCache.containsKey(block.getHashHexString())) {
            return;
        }
        proposalCache.put(block.getHashHexString(), true);
        receiveBlocks(Collections.singletonList(block));
        context.relay();
    }

    private void onStatus(Context context, PeerServer server) {
        NexusChainOuterClass.Status status = context.getPayload().getStatus();
        Block best = stateDB.getBestBlock();

        // PLAN-002：记录已知对端最高高度（本地出块抑制依据）
        long peerHeight = status.getCurrentHeight();
        if (peerHeight > knownMaxPeerHeight) {
            knownMaxPeerHeight = peerHeight;
        }

        // 拉黑创世区块不相同的节点
        if (!Arrays.equals(genesis.getHash(), status.getGenesisHash().toByteArray())) {
            context.block();
            context.exit();
            return;
        }

        if (status.getCurrentHeight() >= best.nHeight
                && !Arrays.equals(
                status.getBestBlockHash().toByteArray(), best.getHash())
        ) {
            long stopHeight = status.getCurrentHeight();
            if (stopHeight >= best.nHeight + maxBlocksPerTransfer) {
                stopHeight = best.nHeight + maxBlocksPerTransfer - 1;
            }
            GetBlockQuery getBlockQuery = new GetBlockQuery(best.nHeight, status.getCurrentHeight());
            getBlockQuery.clip(maxBlocksPerTransfer, false);
            NexusChainOuterClass.GetBlocks req = NexusChainOuterClass.GetBlocks.newBuilder()
                    .setStartHeight(getBlockQuery.start)
                    .setStopHeight(getBlockQuery.stop)
                    .setClipDirection(NexusChainOuterClass.ClipDirection.CLIP_TAIL).build();
            logger.info("require blocks start from " + req.getStartHeight() + " stop at " + req.getStopHeight());
            server.dial(context.getPayload().getRemote(), req);
        }
    }

    private void onGetStatus(Context context, PeerServer server) {
        Block best = stateDB.getBestBlock();
        NexusChainOuterClass.Status resp = NexusChainOuterClass.Status.newBuilder()
                .setBestBlockHash(ByteString.copyFrom(best.getHash()))
                .setCurrentHeight(best.nHeight)
                .setGenesisHash(ByteString.copyFrom(genesis.getHash()))
                .build();
        context.response(resp);
    }

    private synchronized void receiveBlocks(List<Block> blocks) {
        logger.info("blocks received start from " + blocks.get(0).nHeight + " stop at " + blocks.get(blocks.size() - 1).nHeight);
        blocks = blocks.subList(0, maxBlocksPerTransfer > blocks.size() ? blocks.size() : maxBlocksPerTransfer);
        List<Block> validBlocks = new ArrayList<>();
        for (Block b : blocks) {
            if (b == null || b.nHeight == 0) {
                continue;
            }
            Result res = rule.validateBlock(b);
            if (!res.isSuccess()) {
                logger.error("invalid block received reason = " + res.getMessage());
                continue;
            }
            Result resCheckPointRule = checkPointRule.validateBlock(b);
            if (!resCheckPointRule.isSuccess()) {
                logger.error("invalid block received reason = " + res.getMessage());
                continue;
            }
            validBlocks.add(b);
        }
        if (validBlocks.size() > 0) {
            BlocksCache blocksWritable = orphanBlocksManager.removeAndCacheOrphans(validBlocks);
            pendingBlocksManager.addPendingBlocks(blocksWritable);
        }
    }

    @Override
    public void onApplicationEvent(NewBlockMinedEvent event) {
        if (server == null) {
            return;
        }
        proposalCache.put(event.getBlock().getHashHexString(), true);
        server.broadcast(NexusChainOuterClass.Proposal.newBuilder().setBlock(Utils.encodeBlock(event.getBlock())).build());
    }
}
