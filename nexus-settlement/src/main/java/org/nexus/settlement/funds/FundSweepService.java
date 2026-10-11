package org.nexus.settlement.funds;

/**
 * 资金归集服务接口。
 * <p>
 * 负责将分散资金按归集订单归集到指定地址，并支持自动归集与冷钱包转移。
 * </p>
 */
public interface FundSweepService {

    /**
     * 执行单笔归集。
     *
     * @param order 归集订单
     * @return 处理后的归集订单
     */
    CollectionOrder sweep(CollectionOrder order);

    /**
     * 自动归集：扫描所有待归集资金并批量执行。
     *
     * @return 本次自动归集处理的订单数
     */
    int autoSweep();

    /**
     * 转移至冷钱包（**无审批面**：P0 修复 2026-10-11 后，注入多签审批服务时此方法拒绝执行）。
     *
     * @return 转移的订单数
     * @throws IllegalStateException 已装配多签审批（生产）时——冷钱包转移必须走
     *         {@link #transferToCold(String)} 的审批版
     */
    int transferToCold();

    /**
     * 转移至冷钱包（**多签审批版**，P0 修复 2026-10-11）。
     *
     * <p>热→冷是资金出热钱包的动作，必须经多签审批闸门。实现要求：</p>
     * <ol>
     *   <li>{@code approvalId} 对应的审批请求状态为 <b>APPROVED</b>；</li>
     *   <li>该审批请求的 {@code orderId} 必须是冷钱包转移标识（防"拿别的审批来转账"）。</li>
     * </ol>
     *
     * @param approvalId 多签审批请求 ID
     * @return 转移的订单数
     * @throws IllegalStateException 未审批 / 审批用途不符 / 未装配审批服务
     */
    int transferToCold(String approvalId);
}
