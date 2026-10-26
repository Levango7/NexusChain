package org.nexus.bridge.messaging;

import java.util.Objects;

/**
 * 跨链消息**事件**（链上事实的链下抽象，PLAN-046）。
 *
 * <p>与 {@link CrossChainMessage} 的分工：{@code ChainEvent} 表达"源链上发生了
 * 一件事"（哪条链、哪个合约、什么负载、第几笔），<b>不含 messageId/签名/状态</b>——
 * 这些是中继层（{@link MessageFormatter}/{@link MessageRelayer}）的职责。
 * 事件源（{@link ChainEventSource}）只产事件，由 {@link BridgeEventSourceWorker}
 * 转成消息并走既有中继链路。</p>
 *
 * <p><b>顺序保证</b>：{@code nonce} 必须按 (sourceChain, nonce) 单调递增——
 * 与 {@link MessageStore#getMaxNonce(String)} 的顺序检查同口径，事件源
 * 重启后若从旧 nonce 重新产出，中继层会拒绝（见
 * {@link BridgeEventSourceWorker} 的逐事件容错）。</p>
 *
 * @param sourceChain     源链 ID
 * @param targetChain     目标链 ID
 * @param sourceContract  源合约地址
 * @param targetContract  目标合约地址
 * @param payload         消息负载
 * @param nonce           该源链上的事件序号（严格单调）
 * @since 2.77.0
 */
public record ChainEvent(String sourceChain,
                         String targetChain,
                         String sourceContract,
                         String targetContract,
                         MessagePayload payload,
                         long nonce) {

    public ChainEvent {
        Objects.requireNonNull(sourceChain, "sourceChain");
        Objects.requireNonNull(targetChain, "targetChain");
        Objects.requireNonNull(sourceContract, "sourceContract");
        Objects.requireNonNull(targetContract, "targetContract");
        Objects.requireNonNull(payload, "payload");
        if (sourceChain.isEmpty() || targetChain.isEmpty()) {
            throw new IllegalArgumentException("sourceChain/targetChain must not be empty");
        }
    }
}
