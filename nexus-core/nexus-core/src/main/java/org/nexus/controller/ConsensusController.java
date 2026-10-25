/*
 * Copyright (c) [2018]
 * This file is part of the java-nexuscore
 *
 * The java-nexuscore is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The java-nexuscore is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with the java-nexuscore. If not, see <http://www.gnu.org/licenses/>.
 */

package org.nexus.controller;

import org.apache.commons.codec.binary.Hex;
import org.bouncycastle.util.Arrays;
import org.springframework.context.ApplicationContext;
import org.nexus.ApiResult.APIResult;
import org.nexus.ipc.IpcConfig;
import org.nexus.p2p.entity.GetBlockQuery;
import org.nexus.p2p.entity.Status;
import org.nexus.encoding.JSONEncodeDecoder;
import org.nexus.core.Block;
import org.nexus.core.ChainHeadProvider;
import org.nexus.core.TransactionPool;
import org.nexus.core.NexusChainBlockChain;
import org.nexus.core.account.AccountStoreHealth;
import org.nexus.core.account.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.nexus.service.Impl.CommandServiceImpl;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.nexus.controller.ConsensusResult.ERROR;
import static org.nexus.controller.ConsensusResult.SUCCESS;

@RestController
public class ConsensusController {
    static final int MAX_BLOCKS_IN_TRANSIT_PER_PEER = 50;
    private static final Logger logger = LoggerFactory.getLogger(ConsensusController.class);

    @Value("${nexus.consensus.enable-mining}")
    boolean enableMining;

    @Autowired
    private JSONEncodeDecoder codec;

    @Autowired
    private NexusChainBlockChain bc;

    @Autowired
    private ChainHeadProvider chainHeadProvider;

    @Autowired
    private AccountStoreHealth accountStoreHealth;

    /** 最终性投票补写队列观测（PLAN-018；未装配 pos 共识时为 null）。 */
    @Autowired(required = false)
    private org.nexus.consensus.finality.FinalityOutboxManager finalityOutboxManager;
    /** 待补写区块队列观测（PLAN-019；未装配时为 null）。 */
    @Autowired(required = false)
    private org.nexus.core.PendingBlocksManager pendingBlocksManager;

    /** 区块写库失败聚合观测（PLAN-039；未装配时为 null）。 */
    @Autowired(required = false)
    private org.nexus.db.StateDB stateDB;

    @Autowired
    private Block genesis;

    @Autowired
    private TransactionPool pool;

    @Autowired
    private CommandServiceImpl commandService;

    @Autowired
    private PacketCache cache;

    @Autowired
    private ApplicationContext context;

    @Value("${nexus.consensus.allow-fork}")
    private boolean allowFork;

    @Autowired
    IpcConfig ipcConfig;

    @GetMapping(value = "/consensus/blocks")
    public Object handleGetBlocks(
            @RequestParam(name = "start") long start,
            @RequestParam(name = "stop") long stop,
            @RequestParam(name = "clipFromStop") boolean clipFromStop
    ) {
        // clip interval
        GetBlockQuery query = new GetBlockQuery(start, stop).clip(MAX_BLOCKS_IN_TRANSIT_PER_PEER, clipFromStop);

        logger.info("get blocks received start height = " + start + " stop height = " + stop);

        List<Block> blocksToSend = bc.getBlocks(query.start, query.stop, MAX_BLOCKS_IN_TRANSIT_PER_PEER, clipFromStop);
        if (blocksToSend != null && blocksToSend.size() > 0) {
            return new String(codec.encodeBlocks(blocksToSend));
        }
        return ERROR("blocks not founded between " + start + " " + stop);
    }

    @GetMapping(value = "/consensus/status", produces = "application/json")
    public Object getStatus() {
        ConsensuEntity.Status status = new ConsensuEntity.Status();
        Block best = chainHeadProvider.currentHeader();
        status.version = best.nVersion;
        status.currentHeight = best.nHeight;
        status.bestBlockHash = best.getHash();
        status.genesisHash = genesis.getHash();
        return codec.encode(status);
    }

    /** 验证人集分发（PLAN-032；rest 形态无 grpc P2P 广播通道时经此分发）。 */
    @Autowired(required = false)
    private org.nexus.sync.SyncManager syncManager;

    /**
     * 验证人集分发端点（PLAN-032）：返回"全表回放载荷"的 base64 数组——
     * 与 grpc 全表回放**同一构建来源**（存量签名载荷逐字节复用、老行退化重建），
     * 因此接收端可走与 grpc 完全相同的验签/重放/幂等注册路径。
     * 未装配 SyncManager 时返回空数组（不 500）。
     */
    @GetMapping(value = "/consensus/validator-set", produces = "application/json")
    public Object getValidatorSet() {
        if (syncManager == null) {
            return codec.encode(Collections.emptyList());
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        for (byte[] payload : syncManager.validatorSetReplayPayloadsForRest()) {
            out.add(java.util.Base64.getEncoder().encodeToString(payload));
        }
        return codec.encode(out);
    }

    /**
     * 追赶观测（PLAN-033 切片 B）：只读。与 rest 对端协议对象
     * （{@code /consensus/status}）分离——观测字段不进协议对象。
     * <p>注意：新增 P2P/运维对等端点必须同步加入 {@code WebSecurityConfig} 的
     * {@code LocalRpcInterceptor} 白名单（PLAN-032 踩坑：漏加 ⇒ 200 空响应静默失效）。</p>
     */
    @GetMapping(value = "/consensus/catch-up", produces = "application/json")
    public Object getCatchUp() {
        if (syncManager == null) {
            return codec.encode(Collections.emptyMap());
        }
        return codec.encode(syncManager.catchUpSnapshot());
    }

    /**
     * 账户库降级观测端点；不依赖数据库，PG 故障期间仍应返回 DEGRADED。
     */
    @GetMapping(value = "/consensus/storage-status", produces = "application/json")
    public Object getStorageStatus() {
        return codec.encode(accountStoreHealth.snapshot());
    }

    /**
     * 最终性投票补写队列观测（PLAN-018）。
     *
     * <p>返回 local/received/数据库三层待补写票数与最近一次失败原因。
     * 未装配最终性共识（非 pos 模式）时返回 {@code enabled=false}。</p>
     */
    @GetMapping(value = "/consensus/finality-outbox", produces = "application/json")
    public Object getFinalityOutboxStatus() {
        if (finalityOutboxManager == null) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("enabled", false);
            return codec.encode(data);
        }
        Map<String, Object> data = new LinkedHashMap<>(finalityOutboxManager.status().snapshot());
        data.put("enabled", true);
        return codec.encode(data);
    }

    /**
     * 待补写区块队列观测（PLAN-019）。
     *
     * <p>{@code memoryPending} 是内存重试队列长度，{@code databasePending} 是持久化
     * outbox 行数（-1 表示不可读/未装配）。</p>
     */
    @GetMapping(value = "/consensus/block-outbox", produces = "application/json")
    public Object getBlockOutboxStatus() {
        if (pendingBlocksManager == null) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("enabled", false);
            return codec.encode(data);
        }
        return codec.encode(pendingBlocksManager.outboxStatus());
    }

    /**
     * 区块写库失败聚合观测（PLAN-039 / roadmap #29 ①）。
     *
     * <p>内存计数、**不依赖数据库**——PG 故障期间仍可读：{@code writeFailures}
     * 增长说明有区块被跳过落库（后续确认轮次会补写，见 {@code StateDB.writeBlock}
     * 的 A2 修复注释）；{@code stateUpdateTimeouts} 为非零时是更严重的信号
     * （状态更新等待超时、pendingBlock 被强制清空）。</p>
     */
    @GetMapping(value = "/consensus/block-writer", produces = "application/json")
    public Object getBlockWriterStatus() {
        if (stateDB == null) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("enabled", false);
            return codec.encode(data);
        }
        Map<String, Object> data = new LinkedHashMap<>(stateDB.blockWriteStats().snapshot());
        data.put("enabled", true);
        return codec.encode(data);
    }

    @PostMapping(value = "/consensus/status", produces = "application/json")
    public Object handleStatus(@RequestBody byte[] body, HttpServletRequest request) {
        Block header = chainHeadProvider.currentHeader();
        Status status = codec.decode(body, Status.class);
        if (status == null) {
            logger.error("invalid request accepted from " + request.getRemoteAddr());
            return ERROR("invalid request incountered");
        }
        logger.info("receive message type = status, height = " + status.currentHeight + " hash = " + Hex.encodeHexString(status.bestBlockHash));
        if (!Arrays.areEqual(status.genesisHash, genesis.getHash())) {
            return ERROR("genesis hash not equal");
        }
        if (header.nHeight > status.currentHeight) {
            return SUCCESS("received");
        }
        if (header.nHeight == status.currentHeight) {
            // 同一高度直接确认，避免状态握手路径查询数据库；PG 短时故障时
            // 这里不能因为 bc.hasBlock(...) 把整个 /consensus/status 打成 500。
            if (status.bestBlockHash == null
                    || !Arrays.areEqual(header.getHash(), status.bestBlockHash)) {
                logger.warn("peer status same height but different best hash: local={} peer={}",
                        Hex.encodeHexString(header.getHash()),
                        status.bestBlockHash == null ? "null" : Hex.encodeHexString(status.bestBlockHash));
            }
            return SUCCESS("received");
        }

        // clip interval
        GetBlockQuery query = new GetBlockQuery(
                header.nHeight, status.currentHeight
        ).clip(
                MAX_BLOCKS_IN_TRANSIT_PER_PEER, false
        );
        if (ipcConfig.getP2pMode().equals("rest")) {
            context.getBean(SyncClient.class).getBlocks(query.start, query.stop, false);
        }
        return SUCCESS("received, start synchronizing");
    }

    @PostMapping(value = "/consensus/blocks", produces = "application/json")
    public Object handleProposal(@RequestBody byte[] body, HttpServletRequest request) {
        if (!allowFork) {
            return ERROR("reject proposal, because fork is not allowed");
        }
        Block b = codec.decodeBlock(body);
        if (b == null) {
            logger.error("invalid request accepted from " + request.getRemoteAddr());
            return ERROR("invalid block proposal");
        }
        if (ipcConfig.getP2pMode().equals("rest")) {
            context.getBean(SyncClient.class).receiveBlocks(Collections.singletonList(b));
        }
        return SUCCESS("proposal received");
    }

    @PostMapping(value = "/consensus/transactions", produces = "application/json")
    public Object handleTransactions(@RequestBody byte[] body, HttpServletRequest request) {
        Packet packet = codec.decode(body, Packet.class);
        if (packet == null || packet.ttl == 0) {
            return ERROR("ttl timeout");
        }
        if (cache.hasReceived(packet)) {
            return ERROR("the packet has recevied before");
        }
        List<Transaction> txs = codec.decodeTransactions(packet.data);
        if (txs == null || txs.size() == 0) {
            return SUCCESS("transaction received successful");
        }
        for (Transaction tran : txs) {
            byte[] traninfo = tran.toRPCBytes();
            APIResult apiResult = commandService.verifyTransfer(traninfo);
            if (apiResult.getCode() == 5000) {
                logger.info("transaction Check failure,TxHash=" + Hex.encodeHexString(tran.getHash()) + ",message:" + apiResult.getMessage());
                return ERROR("transaction Check failure,TxHash=" + Hex.encodeHexString(tran.getHash()) + ",message:" + apiResult.getMessage());
            }
        }
        return SUCCESS("transaction received successful");
    }
}
