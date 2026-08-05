package org.nexus.analytics.onchain;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link TransactionGraphService} 默认实现。
 *
 * <p>基于 {@link TransactionDataSource} 提供的链上交易构建地址-交易关系图谱：
 * <ul>
 *   <li>{@link #buildGraph}：以指定地址为根做 BFS，按深度收集 N 跳子图</li>
 *   <li>{@link #findPath}：BFS 查找从源地址到目标地址的资金路径</li>
 *   <li>{@link #getCluster}：按共同对手方启发式聚类，返回地址所属簇</li>
 * </ul>
 *
 * <p>当前为进程内即时构建（每次调用基于数据源重新构图），适用于中小规模
 * 数据集；大规模场景应替换为图存储引擎（Neo4j / JanusGraph）。
 */
@Slf4j
@Service
public class DefaultTransactionGraphService implements TransactionGraphService {

    /** 邻接表：地址 → 对手方地址集合 */
    private final Map<String, Set<String>> adjacency = new HashMap<>();

    /** 边上的交易哈希：from -> to -> 交易哈希 */
    private final Map<String, Map<String, String>> edgeTxHash = new HashMap<>();

    private final TransactionDataSource dataSource;

    public DefaultTransactionGraphService(TransactionDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public AddressCluster buildGraph(String address, int depth) {
        if (address == null || address.isBlank()) {
            return emptyCluster();
        }
        rebuildIndex();

        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        Map<String, Integer> distance = new HashMap<>();
        queue.add(address);
        visited.add(address);
        distance.put(address, 0);

        long txCount = 0;
        BigInteger totalVolume = BigInteger.ZERO;

        while (!queue.isEmpty()) {
            String current = queue.poll();
            int currentDepth = distance.get(current);
            if (currentDepth >= depth) {
                continue;
            }
            for (String neighbor : adjacency.getOrDefault(current, Set.of())) {
                if (!visited.contains(neighbor)) {
                    visited.add(neighbor);
                    distance.put(neighbor, currentDepth + 1);
                    queue.add(neighbor);
                }
            }
        }

        // 统计子图内交易量
        for (OnChainTransaction tx : dataSource.fetchAll()) {
            if (visited.contains(tx.getFromAddress()) || visited.contains(tx.getToAddress())) {
                txCount++;
                if (tx.getAmount() != null) {
                    totalVolume = totalVolume.add(tx.getAmount());
                }
            }
        }

        return AddressCluster.builder()
                .clusterId("GRAPH-" + address)
                .addresses(new ArrayList<>(visited))
                .label("SUBGRAPH")
                .confidence(1.0)
                .txCount(txCount)
                .totalVolume(totalVolume.longValue())
                .build();
    }

    @Override
    public Optional<FundFlowTrace> findPath(String from, String to) {
        if (from == null || to == null || from.isBlank() || to.isBlank()) {
            return Optional.empty();
        }
        if (from.equals(to)) {
            return Optional.of(FundFlowTrace.builder()
                    .fromAddress(from).toAddress(to)
                    .path(List.of(from)).txHashes(List.of())
                    .amount(BigInteger.ZERO).hops(0).build());
        }
        rebuildIndex();

        // BFS 记录父节点以回溯路径
        Map<String, String> parent = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(from);
        visited.add(from);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String neighbor : adjacency.getOrDefault(current, Set.of())) {
                if (!visited.contains(neighbor)) {
                    visited.add(neighbor);
                    parent.put(neighbor, current);
                    if (neighbor.equals(to)) {
                        return Optional.of(tracePath(from, to, parent));
                    }
                    queue.add(neighbor);
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public AddressCluster getCluster(String address) {
        if (address == null || address.isBlank()) {
            return emptyCluster();
        }
        rebuildIndex();

        // 启发式聚类：将共享至少一个对手方的地址归入同一簇
        Set<String> cluster = new LinkedHashSet<>();
        cluster.add(address);
        Set<String> myCounterparties = adjacency.getOrDefault(address, Set.of());

        for (String other : adjacency.keySet()) {
            if (other.equals(address)) {
                continue;
            }
            Set<String> otherCounterparties = adjacency.get(other);
            for (String cp : myCounterparties) {
                if (otherCounterparties.contains(cp)) {
                    cluster.add(other);
                    break;
                }
            }
        }

        return AddressCluster.builder()
                .clusterId("CLUSTER-" + address)
                .addresses(new ArrayList<>(cluster))
                .label("HEURISTIC")
                .confidence(cluster.size() > 1 ? 0.7 : 0.5)
                .txCount((long) dataSource.fetchByAddress(address).size())
                .totalVolume(BigInteger.ZERO.longValue())
                .build();
    }

    @Override
    public List<AddressCluster> getClusters(List<String> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            return List.of();
        }
        List<AddressCluster> result = new ArrayList<>();
        Set<String> seenClusterIds = new HashSet<>();
        for (String address : addresses) {
            AddressCluster cluster = getCluster(address);
            if (cluster.getClusterId() != null && seenClusterIds.add(cluster.getClusterId())) {
                result.add(cluster);
            }
        }
        return result;
    }

    /** 基于数据源重建邻接索引。 */
    private void rebuildIndex() {
        adjacency.clear();
        edgeTxHash.clear();
        for (OnChainTransaction tx : dataSource.fetchAll()) {
            if (tx.getFromAddress() == null || tx.getToAddress() == null) {
                continue;
            }
            adjacency.computeIfAbsent(tx.getFromAddress(), k -> new HashSet<>()).add(tx.getToAddress());
            adjacency.computeIfAbsent(tx.getToAddress(), k -> new HashSet<>()).add(tx.getFromAddress());
            edgeTxHash.computeIfAbsent(tx.getFromAddress(), k -> new HashMap<>())
                    .put(tx.getToAddress(), tx.getTxHash());
        }
        log.debug("Graph index rebuilt: nodes={}, txs={}", adjacency.size(), dataSource.fetchAll().size());
    }

    private FundFlowTrace tracePath(String from, String to, Map<String, String> parent) {
        List<String> path = new ArrayList<>();
        List<String> txHashes = new ArrayList<>();
        String current = to;
        path.add(current);
        while (!current.equals(from)) {
            String prev = parent.get(current);
            String hash = edgeTxHash.getOrDefault(prev, Map.of()).get(current);
            if (hash != null) {
                txHashes.add(0, hash);
            }
            path.add(0, prev);
            current = prev;
        }
        return FundFlowTrace.builder()
                .fromAddress(from)
                .toAddress(to)
                .path(path)
                .txHashes(txHashes)
                .amount(BigInteger.ZERO)
                .hops(path.size() - 1)
                .build();
    }

    private AddressCluster emptyCluster() {
        return AddressCluster.builder()
                .clusterId(null)
                .addresses(List.of())
                .label("UNKNOWN")
                .confidence(0.0)
                .txCount(0L)
                .totalVolume(0L)
                .build();
    }
}
