package org.nexus.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.nexus.consensus.pow.ConsensusConfig;
import org.nexus.core.*;
import org.nexus.core.validate.BasicRule;
import org.nexus.core.validate.Result;
import org.nexus.encoding.JSONEncodeDecoder;
import org.nexus.p2p.entity.GetBlockQuery;
import org.nexus.p2p.entity.Status;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@ConditionalOnProperty(name = "p2p.mode", havingValue = "rest")
@Component
public class SyncClient {
    private static final int MAX_BLOCKS_IN_TRANSIT_PER_PEER = 50;
    private static final Logger logger = LoggerFactory.getLogger(SyncClient.class);

    private AtomicInteger counter = new AtomicInteger();

    @Value("${nexus.consensus.enable-mining}")
    boolean enableMining;

    @Autowired
    private RPCClient rpcClient;

    @Autowired
    private JSONEncodeDecoder codec;

    @Autowired
    private ConsensusConfig consensusConfig;

    @Autowired
    private BasicRule rule;

    @Autowired
    private OrphanBlocksManager orphanBlocksManager;

    @Autowired
    private PendingBlocksManager pendingBlocksManager;

    @Autowired
    private NexusChainBlockChain bc;

    @Autowired
    private ChainHeadProvider chainHeadProvider;

    @Autowired
    private Block genesis;

    @Async
    public void getBlocks(long start, long stop, boolean clipFromStop) {
        Map<String, String> params = new HashMap<>();
        params.put("start", Long.toString(start));
        params.put("stop", Long.toString(stop));
        params.put("clipFromStop", Boolean.toString(clipFromStop));
        for (String hostPort : consensusConfig.getPeers()) {
            rpcClient.get("http://" + hostPort + "/consensus/blocks", params, (byte[] body) -> {
                List<Block> blocks = codec.decodeBlocks(body);
                if (blocks == null) {
                    logger.error("get blocks from " + hostPort + " failed, consider correct your boot nodes");
                    return null;
                }
                receiveBlocks(blocks);
                return null;
            });
        }
    }

    @Async
    public Object receiveBlocks(List<Block> blocks) {
        List<Block> validBlocks = new ArrayList<>();
        for (Block b : blocks) {
            if (b == null || b.nHeight == 0) {
                continue;
            }
            Result res;
            try {
                res = rule.validateBlock(b);
            } catch (RuntimeException e) {
                // PLAN-019：校验过程本身可能因为**存储不可用**而抛异常（读父块/读账户失败），
                // 这时不能把它当成"区块无效"丢在门口 —— 旧行为里异常直接逃出 @Async 方法，
                // 这批收到的区块被**静默丢弃**，只能等后续 P2P 同步再拉一遍。
                // 现在交给 PendingBlocksManager：它会再试一次，仍失败则把区块
                // （含写入意图）存进内存队列与持久化 outbox，存储恢复后补写。
                logger.warn("Block validation failed with a storage error (height={}); "
                        + "handing the batch to the pending-blocks manager for durable capture: {}",
                        b.nHeight, e.getMessage());
                validBlocks.add(b);
                pendingBlocksManager.addPendingBlocks(
                        orphanBlocksManager.removeAndCacheOrphans(validBlocks));
                return null;
            }
            if (!res.isSuccess()) {
                if (isStorageUnavailable(res)) {
                    // PLAN-019：存储不可用 ≠ 区块无效。旧行为把它当"invalid block"丢掉，
                    // 现在交给 PendingBlocksManager 走重试与持久化 outbox 通道。
                    logger.warn("Block validation hit a storage outage (height={}); "
                            + "handing the batch to the pending-blocks manager for durable capture: {}",
                            b.nHeight, res.getMessage());
                    validBlocks.add(b);
                    pendingBlocksManager.addPendingBlocks(
                            orphanBlocksManager.removeAndCacheOrphans(validBlocks));
                    return null;
                }
                logger.error("invalid block received reason = " + res.getMessage());
                continue;
            }
            logger.info("receive block = " + new String(codec.encode(b)));
            validBlocks.add(b);
        }
        if (validBlocks.size() > 0) {
            logger.info("receive blocks startListening from " + validBlocks.get(0).nHeight + " stop at " + validBlocks.get(validBlocks.size() - 1).nHeight);
            BlocksCache blocksWritable = orphanBlocksManager.removeAndCacheOrphans(validBlocks);
            pendingBlocksManager.addPendingBlocks(blocksWritable);
        }
        return null;
    }

    /**
     * 校验结果是否表示"存储不可用"（而不是"区块无效"）。
     *
     * <p>判定依据是 {@code MerkleRule} 给存储故障打的前缀标记
     * （PLAN-018 引入）：这类失败必须重试，不能当坏块丢掉。</p>
     */
    private static boolean isStorageUnavailable(Result res) {
        return res.getMessage() != null
                && res.getMessage().startsWith(
                        org.nexus.core.validate.MerkleRule.ACCOUNT_STORE_UNAVAILABLE_PREFIX);
    }

    @Scheduled(fixedRate = 15 * 1000)
    public void sendStatus() {
        if (!enableMining) {
            return;
        }
        ConsensuEntity.Status status = new ConsensuEntity.Status();
        Block best = chainHeadProvider.currentHeader();
        status.version = best.nVersion;
        status.currentHeight = best.nHeight;
        status.bestBlockHash = best.getHash();
        status.genesisHash = genesis.getHash();
        rpcClient.broadcast("/consensus/status", codec.encode(status));
    }

    @Scheduled(fixedRate = 30 * 1000)
    public void getStatus() {
        if (enableMining) {
            return;
        }
        logger.info("try to sync from nexus seeds");
        String hostPort = consensusConfig.getPeers().get(counter.incrementAndGet() % consensusConfig.getPeers().size());
        rpcClient.get("http://" + hostPort + "/consensus/status", new HashMap<>(), (byte[] resp) -> {
            Status status = codec.decode(resp, Status.class);
            if (status == null) {
                logger.error("invalid status received " + new String(resp));
                return null;
            }
            Block header = chainHeadProvider.currentHeader();
            if (status.currentHeight <= header.nHeight) {
                return null;
            }
            // clip interval
            GetBlockQuery query = new GetBlockQuery(header.nHeight, status.currentHeight)
                    .clip(ConsensusController.MAX_BLOCKS_IN_TRANSIT_PER_PEER, false);
            getBlocks(query.start, query.stop, false);
            return null;
        });
    }

    @Async
    public void proposalBlock(Block block) {
        rpcClient.broadcast("/consensus/blocks", codec.encodeBlock(block));
    }

    @Scheduled(fixedRate = 30 * 1000)
    public void syncOrphan() {
        for (Block b : orphanBlocksManager.getInitials()) {
            logger.info("try to sync orphans");
            long startHeight = b.nHeight - MAX_BLOCKS_IN_TRANSIT_PER_PEER + 1;
            if (startHeight <= 0) {
                startHeight = 1;
            }
            getBlocks(startHeight, b.nHeight, true);
        }
    }

}
