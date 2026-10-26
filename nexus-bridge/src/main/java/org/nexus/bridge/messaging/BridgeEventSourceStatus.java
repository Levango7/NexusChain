package org.nexus.bridge.messaging;

/**
 * 桥消息**事件源**中继的就绪/进度快照（PLAN-046，只读）。
 *
 * <p>把"前段是否通电、从哪条链拉、拉到第几笔、中继成功/失败多少"变成可观测
 * 信号——与 {@link BridgeMessagingStatus}（装配快照）互补：后者回答"插座通没
 * 通电"，本记录回答"前段有没有在产消息、产得顺不顺"。</p>
 *
 * @param sourceEnabled 事件源开关是否开启（开关关时此 Bean 不存在）
 * @param sourceChain   事件源源链 ID
 * @param targetChain   默认目标链 ID
 * @param sourceType    事件源实现类名（如 SimulatedChainEventSource）
 * @param pollCount     轮询次数（进程内累计）
 * @param relayedCount  成功签名入库的事件条数
 * @param failedCount   被逐事件容错跳过的事件条数
 * @param lastNonce     最近一次产出的事件的 nonce（无为 -1）
 * @param lastError     最近一次错误摘要（无为 null）
 *
 * @since 2.77.0
 */
public record BridgeEventSourceStatus(boolean sourceEnabled,
                                      String sourceChain,
                                      String targetChain,
                                      String sourceType,
                                      long pollCount,
                                      long relayedCount,
                                      long failedCount,
                                      long lastNonce,
                                      String lastError) {
}
