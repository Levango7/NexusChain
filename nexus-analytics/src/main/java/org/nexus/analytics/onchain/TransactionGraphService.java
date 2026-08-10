package org.nexus.analytics.onchain;

import java.util.List;
import java.util.Optional;

/**
 * 链上交易图谱服务。
 *
 * <p>负责基于链上交易历史构建地址-交易关系图谱，支持路径发现与地址聚类。
 *
 * <p>典型场景：
 * <ul>
 *   <li>资金流向溯源（反洗钱 / 取证）</li>
 *   <li>地址聚类（识别同一实体的多个地址）</li>
 *   <li>关联交易路径发现</li>
 * </ul>
 */
public interface TransactionGraphService {

    /**
     * 以指定地址为根、按深度构建交易图谱。
     *
     * @param address 起始地址
     * @param depth   图谱深度（1 = 直接对手方，2 = 二跳，依此类推）
     * @return 包含该地址及其 N 跳关联交易的子图
     */
    AddressCluster buildGraph(String address, int depth);

    /**
     * 在交易图谱中查找从 {@code from} 到 {@code to} 的资金路径。
     *
     * @param from 源地址
     * @param to   目标地址
     * @return 命中路径；若不存在或超出搜索半径则返回 {@link Optional#empty()}
     */
    Optional<FundFlowTrace> findPath(String from, String to);

    /**
     * 获取指定地址所属的地址簇（聚类结果）。
     *
     * @param address 待查询地址
     * @return 地址簇
     */
    AddressCluster getCluster(String address);

    /**
     * 批量获取多个地址所属簇（去重后返回）。
     *
     * @param addresses 地址列表
     * @return 去重后的地址簇列表
     */
    List<AddressCluster> getClusters(List<String> addresses);
}