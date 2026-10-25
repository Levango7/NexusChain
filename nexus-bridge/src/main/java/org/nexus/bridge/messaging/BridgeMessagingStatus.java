package org.nexus.bridge.messaging;

/**
 * 桥 messaging 中继/执行装配的就绪快照（PLAN-037 切片 1，只读）。
 *
 * <p>目的：把"装配是否生效、用什么 store、要求几签、有几个目标链适配器"
 * 变成可观测信号——在切片 1（只装配、不接入真实外部链）阶段，
 * 运维/测试可据此确认插座已通电，而不必翻日志。</p>
 *
 * @param relayEnabled       是否开启装配（开关关时此 Bean 不存在）
 * @param requiredSignatures 多签阈值（来自 {@code nexus.bridge.messaging.required-signatures}）
 * @param messageTimeout     消息超时（秒）
 * @param storeType          实际注入的 MessageStore 实现类名
 * @param targetAdapters     已登记的目标链适配器数量（0 表示切片 2 未接）
 *
 * @since 2.76.0
 */
public record BridgeMessagingStatus(boolean relayEnabled,
                                    int requiredSignatures,
                                    long messageTimeout,
                                    String storeType,
                                    int targetAdapters) {
}
