package org.nexus.bridge.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * **模拟**链事件源（PLAN-046 演示切片；测试床专用，非生产链路）。
 *
 * <p>按固定节拍从内存态"源链"产出跨链消息事件：每次 poll 产出
 * {@code maxEvents} 条（默认 1），nonce 从 {@code startNonce} 起严格单调，
 * 负载为 {@code sim-event:<sourceChain>:<nonce>:<epochMillis>} 的 ARBITRARY
 * 文本——形态与真实链事件转出的消息一致，可跑通
 * format → 签名 → 入库 → 中验 → 执行 的全链路。</p>
 *
 * <h2>如实边界（勿夸大）</h2>
 * <ul>
 *   <li><b>内存态</b>：nonce 游标不落库，进程重启后从 {@code startNonce}
 *       重新开始。若 store 是持久化的（jpa），重启后旧 nonce 会被
 *       {@link MessageStore#getMaxNonce(String)} 的顺序检查拒绝——这是
 *       <b>预期行为</b>（中继层拒绝乱序），由 {@link BridgeEventSourceWorker}
 *       逐事件容错跳过，不会打死循环；真实部署应由真实事件源从链上
 *       游标恢复，而不是从常量起跳；</li>
 *   <li><b>不是真实链</b>：不连接任何外部链、不需要凭证；产出内容不含
 *       真实资金/状态语义；</li>
 *   <li><b>单签</b>：由调用方（中继 worker）用单个 relayer 私钥签名，
 *       不构成多签门限——多签需要真实验证人集合，属未来切片。</li>
 * </ul>
 *
 * @since 2.77.0
 */
public class SimulatedChainEventSource implements ChainEventSource {

    private static final Logger log = LoggerFactory.getLogger(SimulatedChainEventSource.class);

    private final String sourceChain;
    private final String targetChain;
    private final String sourceContract;
    private final String targetContract;
    private final long startNonce;

    /** 下一个待产出事件的 nonce（单调递增，仅内存）。 */
    private final AtomicLong nextNonce;

    public SimulatedChainEventSource(String sourceChain, String targetChain,
                                     String sourceContract, String targetContract,
                                     long startNonce) {
        this.sourceChain = requireText(sourceChain, "sourceChain");
        this.targetChain = requireText(targetChain, "targetChain");
        this.sourceContract = requireText(sourceContract, "sourceContract");
        this.targetContract = requireText(targetContract, "targetContract");
        this.startNonce = startNonce;
        this.nextNonce = new AtomicLong(startNonce);
    }

    @Override
    public List<ChainEvent> pollEvents(int maxEvents) {
        int limit = maxEvents <= 0 ? 1 : maxEvents;
        List<ChainEvent> events = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            long nonce = nextNonce.getAndIncrement();
            String payload = "sim-event:" + sourceChain + ":" + nonce + ":" + System.currentTimeMillis();
            events.add(new ChainEvent(sourceChain, targetChain, sourceContract, targetContract,
                    new MessagePayload(MessagePayload.Type.ARBITRARY, payload), nonce));
        }
        log.debug("simulated source emitted {} event(s) ({}..{})", events.size(),
                events.get(0).nonce(), events.get(events.size() - 1).nonce());
        return events;
    }

    @Override
    public String getSourceChain() {
        return sourceChain;
    }

    @Override
    public String getTargetChain() {
        return targetChain;
    }

    /** 配置的起始 nonce（重启游标语义见类注释）。 */
    public long getStartNonce() {
        return startNonce;
    }

    /** 下一个将被产出的 nonce（观测/测试用）。 */
    public long peekNextNonce() {
        return nextNonce.get();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        return value;
    }
}
