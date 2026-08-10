package org.nexus.bridge.safety;

/**
 * 紧急暂停服务接口。
 *
 * <p>提供桥的紧急暂停 / 恢复与状态查询能力，
 * 用于应对安全事件。</p>
 *
 * @since 1.2
 */
public interface EmergencyPauseService {

    /**
     * 暂停指定桥。
     *
     * @param bridgeId 桥 ID
     */
    void pauseBridge(String bridgeId);

    /**
     * 恢复指定桥。
     *
     * @param bridgeId 桥 ID
     */
    void resumeBridge(String bridgeId);

    /**
     * 查询所有桥当前状态。
     *
     * @return 桥状态映射（bridgeId → status）
     */
    java.util.Map<String, String> getPossibleStatus();
}