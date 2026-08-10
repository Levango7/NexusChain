package org.nexus.bridge.relayer;

/**
 * Relayer 网络接口。
 *
 * <p>定义跨链中继网络的请求提交、状态查询、Relayer 选取
 * 与中继证明验证能力。</p>
 *
 * @since 1.2
 */
public interface RelayerNetwork {

    /**
     * 提交中继请求。
     *
     * @param request 中继请求
     * @return 请求 ID
     */
    String submitRelayRequest(RelayRequest request);

    /**
     * 查询指定 relayer 当前状态。
     *
     * @param relayerId relayer ID
     * @return relayer 实体
     */
    Relayer getRelayerStatus(String relayerId);

    /**
     * 按信誉 / 质押选取最优 relayer。
     *
     * @return 被选中的 relayer
     */
    Relayer selectRelayer();

    /**
     * 验证中继证明合法性。
     *
     * @param proof 中继证明
     * @return 验证通过返回 true
     */
    boolean verifyRelayProof(Object proof);
}