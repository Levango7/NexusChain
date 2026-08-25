package org.nexus.core.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BRIDGE_MINT 重放防护（v2.1.0 安全修复）。
 *
 * <p>以规范化 messageHash（32 字节的 hex，域分隔哈希
 * {@code NEXUS:BRIDGE_MINT:v1 || bridgeTxId || sourceChain || recipient || amount || timelock}）
 * 为幂等键记录"已消费"的铸造。同一 messageHash 只允许成功入账一次：</p>
 *
 * <ul>
 *   <li><b>验证层</b>：{@code BridgeRule.validateBridgeMint} 在多签验签通过后检查，
 *       已消费的 messageHash 直接拒绝交易进入区块；</li>
 *   <li><b>应用层</b>：{@code PaymentTransactionProcessor.processBridgeMint} 在成功
 *       记录铸造后标记消费。同区块内重复交易在处理层被跳过（纵深防御）。</li>
 * </ul>
 *
 * <p>线程安全：基于 {@link ConcurrentHashMap#newKeySet()}。</p>
 *
 * <p><b>已知限制</b>：当前实现为节点内存态，重启后依赖重新同步历史区块恢复；
 * 生产部署应将已消费集合持久化（如随 PaymentStateStore 落库）。</p>
 */
@Component
public class BridgeMintReplayGuard {

    private static final Logger log = LoggerFactory.getLogger(BridgeMintReplayGuard.class);

    /** 已消费的 messageHash（小写 hex）。 */
    private final Set<String> consumedMessageHashes = ConcurrentHashMap.newKeySet();

    /**
     * 判断该 messageHash 是否已被消费（已成功铸造）。
     *
     * @param messageHashHex 规范化 messageHash 的小写 hex（64 字符）
     * @return true 表示已消费，禁止再次铸造
     */
    public boolean isConsumed(String messageHashHex) {
        return messageHashHex != null && consumedMessageHashes.contains(messageHashHex);
    }

    /**
     * 标记 messageHash 已消费。重复标记为幂等操作。
     *
     * @param messageHashHex 规范化 messageHash 的小写 hex（64 字符）
     * @return true 表示本次调用完成了首次标记，false 表示此前已标记过
     */
    public boolean markConsumed(String messageHashHex) {
        if (messageHashHex == null) {
            return false;
        }
        boolean first = consumedMessageHashes.add(messageHashHex);
        if (first) {
            log.info("BRIDGE_MINT replay guard: marked consumed messageHash={}", messageHashHex);
        }
        return first;
    }

    /**
     * 当前已消费条目数（监控/测试用）。
     */
    public int size() {
        return consumedMessageHashes.size();
    }
}
