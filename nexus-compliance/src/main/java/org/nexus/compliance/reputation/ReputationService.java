package org.nexus.compliance.reputation;

/**
 * 信誉评分服务接口。
 * <p>
 * 负责地址的信誉评分查询、事件驱动更新与历史回溯。
 * </p>
 */
public interface ReputationService {

    /**
     * 查询地址当前信誉评分。
     *
     * @param address 地址
     * @return 信誉评分
     */
    ReputationScore getScore(String address);

    /**
     * 基于事件更新地址信誉评分。
     *
     * @param address 地址
     * @param event   触发事件
     * @return 更新后的信誉评分
     */
    ReputationScore updateScore(String address, Object event);

    /**
     * 查询地址信誉历史。
     *
     * @param address 地址
     * @return 信誉评分历史事件列表
     */
    java.util.List<String> getHistory(String address);
}