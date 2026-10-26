package org.nexus.bridge.messaging;

import java.util.List;

/**
 * 跨链消息**事件源**（PLAN-046）：桥消息链路的前段。
 *
 * <p>roadmap #33 欠账的最后一环是"外部链事件 → 构造消息 → 落库"：中继/执行
 * （{@link MessageRelayWorker}）与消息存储自 PLAN-037 切片 1-3 起已装配，
 * 但**没有任何东西在产事件**——此前只有测试代码手工 {@code new} 消息。
 * 本接口把"事件从哪来"变成可替换的插座：</p>
 *
 * <ul>
 *   <li>真实实现（未来）：订阅某条链的事件日志/RPC 过滤器，转为
 *       {@link ChainEvent}——需要链凭证与网络出口，不在本切片范围；</li>
 *   <li>演示实现（本切片）：{@link SimulatedChainEventSource}，内存态
 *       模拟链，只用于测试床/联调，**不是生产链路**。</li>
 * </ul>
 *
 * <p><b>契约</b>：{@link #pollEvents(int)} 返回**尚未被中继消费**的新事件，
 * 数量不超过 {@code maxEvents}；重复 poll 不应重复返回同一 nonce（由实现保证，
 * 进程重启后的游标语义见实现注释）。</p>
 *
 * @since 2.77.0
 */
public interface ChainEventSource {

    /**
     * 拉取一批新事件。
     *
     * @param maxEvents 最多返回条数（&le;0 视为不限制，由实现方兜底）
     * @return 新事件列表（无新事件时为空列表，不得返回 null）
     */
    List<ChainEvent> pollEvents(int maxEvents);

    /**
     * 该事件源覆盖的源链 ID（用于就绪观测；单源实现返回唯一链）。
     */
    String getSourceChain();

    /**
     * 该事件源产出的目标链 ID（就绪观测用；<b>不得</b>通过 poll 副作用获取）。
     */
    String getTargetChain();

    /**
     * 实现类名（就绪观测用，避免把内部状态暴露给运维）。
     */
    default String describe() {
        return getClass().getSimpleName();
    }
}
