package org.nexus.bridge.relayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Relayer 网络默认实现。
 *
 * <p>进程内内存实现，提供完整的中继请求生命周期管理：</p>
 * <ul>
 *   <li>{@link #submitRelayRequest}：校验请求 → 按信誉×质押加权随机选取 relayer →
 *       请求置 RELAYING 并持久化</li>
 *   <li>{@link #getRelayerStatus}：从注册表查询 relayer 状态</li>
 *   <li>{@link #selectRelayer}：在 ACTIVE relayer 中按 (reputation × stake) 加权随机选取</li>
 *   <li>{@link #verifyRelayProof}：校验中继证明的源交易哈希与金额一致性</li>
 * </ul>
 *
 * <p>生产环境需替换为链上注册表 + 分布式 relayer 发现；当前实现保留接口契约，
 * 替换存储层即可。</p>
 *
 * @since 1.2
 */
@Service
public class DefaultRelayerNetwork implements RelayerNetwork {

    private static final Logger logger = LoggerFactory.getLogger(DefaultRelayerNetwork.class);

    /** relayer 注册表：relayerId → Relayer */
    private final Map<String, Relayer> relayers = new ConcurrentHashMap<>();

    /** 中继请求表：requestId → RelayRequest */
    private final Map<String, RelayRequest> requests = new ConcurrentHashMap<>();

    /** 请求分配历史（按提交顺序，供审计） */
    private final List<String> requestOrder = new CopyOnWriteArrayList<>();

    private final SecureRandom random = new SecureRandom();

    /**
     * 注册一个 relayer（供测试 / 链上注册事件同步调用）。
     *
     * @param relayer relayer 实体
     */
    public void registerRelayer(Relayer relayer) {
        if (relayer == null || relayer.getRelayerId() == null) {
            throw new IllegalArgumentException("relayer or relayerId is null");
        }
        relayers.put(relayer.getRelayerId(), relayer);
        logger.info("Relayer registered: id={}, stake={}, reputation={}",
                relayer.getRelayerId(), relayer.getStake(), relayer.getReputationScore());
    }

    /**
     * 完成一个中继请求（relayer 回调，校验通过后置 COMPLETED）。
     *
     * @param requestId 请求 ID
     * @return 更新后的请求；请求不存在返回 null
     */
    public RelayRequest completeRelayRequest(String requestId) {
        RelayRequest request = requests.get(requestId);
        if (request == null) {
            return null;
        }
        request.setStatus(RelayRequestStatus.COMPLETED);
        logger.info("Relay request completed: requestId={}, relayer={}",
                requestId, request.getAssignedRelayerId());
        return request;
    }

    /**
     * 查询中继请求状态。
     *
     * @param requestId 请求 ID
     * @return 请求实体；不存在返回 null
     */
    public RelayRequest getRelayRequest(String requestId) {
        return requestId == null ? null : requests.get(requestId);
    }

    /**
     * 列出全部已注册 relayer（快照）。
     *
     * @return relayer 列表
     */
    public List<Relayer> listRelayers() {
        return new ArrayList<>(relayers.values());
    }

    @Override
    public String submitRelayRequest(RelayRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("relay request is null");
        }
        if (request.getSourceChain() == null || request.getSourceChain().isEmpty()) {
            throw new IllegalArgumentException("sourceChain is required");
        }
        if (request.getTargetChain() == null || request.getTargetChain().isEmpty()) {
            throw new IllegalArgumentException("targetChain is required");
        }
        if (request.getSourceTxHash() == null || request.getSourceTxHash().isEmpty()) {
            throw new IllegalArgumentException("sourceTxHash is required");
        }
        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (request.getSourceChain().equals(request.getTargetChain())) {
            throw new IllegalArgumentException("sourceChain and targetChain must differ");
        }

        Relayer selected = selectRelayer();
        if (selected == null) {
            throw new IllegalStateException("no active relayer available");
        }

        String requestId = "RELAY-" + UUID.randomUUID().toString().replace("-", "");
        request.setRequestId(requestId);
        request.setStatus(RelayRequestStatus.RELAYING);
        request.setAssignedRelayerId(selected.getRelayerId());

        requests.put(requestId, request);
        requestOrder.add(requestId);
        logger.info("Relay request submitted: requestId={}, {} -> {}, amount={}, relayer={}",
                requestId, request.getSourceChain(), request.getTargetChain(),
                request.getAmount(), selected.getRelayerId());
        return requestId;
    }

    @Override
    public Relayer getRelayerStatus(String relayerId) {
        if (relayerId == null || relayerId.isEmpty()) {
            return null;
        }
        return relayers.get(relayerId);
    }

    @Override
    public Relayer selectRelayer() {
        List<Relayer> active = new ArrayList<>();
        double totalWeight = 0;
        for (Relayer r : relayers.values()) {
            if (r.getStatus() != RelayerStatus.ACTIVE) {
                continue;
            }
            double weight = selectionWeight(r);
            if (weight > 0) {
                active.add(r);
                totalWeight += weight;
            }
        }
        if (active.isEmpty()) {
            return null;
        }
        if (active.size() == 1) {
            return active.get(0);
        }
        // Weighted random selection by reputation × stake
        double target = random.nextDouble() * totalWeight;
        double cumulative = 0;
        for (Relayer r : active) {
            cumulative += selectionWeight(r);
            if (target <= cumulative) {
                return r;
            }
        }
        return active.get(active.size() - 1);
    }

    @Override
    public boolean verifyRelayProof(Object proof) {
        if (proof == null) {
            return false;
        }
        if (!(proof instanceof RelayRequest)) {
            logger.warn("verifyRelayProof: unsupported proof type: {}", proof.getClass().getName());
            return false;
        }
        RelayRequest req = (RelayRequest) proof;
        // Verify: request exists, assigned to an ACTIVE relayer, and amounts match
        RelayRequest stored = req.getRequestId() != null ? requests.get(req.getRequestId()) : null;
        if (stored == null) {
            logger.warn("verifyRelayProof: unknown request id: {}", req.getRequestId());
            return false;
        }
        if (stored.getSourceTxHash() == null || !stored.getSourceTxHash().equals(req.getSourceTxHash())) {
            logger.warn("verifyRelayProof: sourceTxHash mismatch for {}", req.getRequestId());
            return false;
        }
        if (stored.getAmount() == null || req.getAmount() == null
                || stored.getAmount().compareTo(req.getAmount()) != 0) {
            logger.warn("verifyRelayProof: amount mismatch for {}", req.getRequestId());
            return false;
        }
        Relayer relayer = stored.getAssignedRelayerId() != null
                ? relayers.get(stored.getAssignedRelayerId()) : null;
        if (relayer == null || relayer.getStatus() != RelayerStatus.ACTIVE) {
            logger.warn("verifyRelayProof: assigned relayer inactive for {}", req.getRequestId());
            return false;
        }
        return true;
    }

    /**
     * 选取权重：信誉分 × 质押额（两者均需为正）。
     */
    private double selectionWeight(Relayer relayer) {
        if (relayer.getReputationScore() <= 0) {
            return 0;
        }
        if (relayer.getStake() == null || relayer.getStake().compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        return relayer.getReputationScore() * relayer.getStake().doubleValue();
    }
}
