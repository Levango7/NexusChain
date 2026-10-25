package org.nexus.bridge.adapter;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 模拟链适配器（PLAN-037 切片 3）——本地闭环验证用，<b>不连任何外部链</b>。
 *
 * <p>目的：让"中继循环 → execute-enabled=true → 记账"这条链路在没有真实链
 * 凭证/网络出口的环境下可端到端验证（CI 与测试床演示）。行为：</p>
 * <ul>
 *   <li>{@link #sendTransaction(byte[])} 立即返回确定性派生的伪 txHash
 *       （{@code SHA-256(chainId ‖ tx ‖ 序号)} 的十六进制），不产生任何网络 IO；</li>
 *   <li>{@link #getTransactionReceipt(String)} 对已提交哈希返回
 *       {@code {"status":"0x1","txHash":...}}（模拟成功回执），未知哈希返回 null；</li>
 *   <li>{@link #getBlockHeight()} 返回"已提交交易数"（模拟高度，仅用于冒烟/日志）；</li>
 *   <li>{@link #callContract(String, String)} 返回 {@code "0x"}（空返回）。</li>
 * </ul>
 *
 * <h2>装配</h2>
 * <p>仅在 {@code nexus.bridge.messaging.relay-enabled=true} 且
 * {@code nexus.bridge.messaging.simulated-target-chains} 列出本链 ID 时，由
 * {@link org.nexus.bridge.messaging.MessagingRelayConfig} 创建（默认不装配；
 * 真实适配器优先，不覆盖）。</p>
 *
 * <h2>勿误用（勿夸大）</h2>
 * <p>本类<b>不是</b>"已接入某条链"：交易与回执均为内存态模拟，进程重启即丢；
 * 真实链接入仍需部署方装配真实 {@link ChainAdapter}（RPC 凭证在网络出口侧）。</p>
 *
 * @since 2.76.0
 */
public class SimulatedChainAdapter implements ChainAdapter {

    /** 已提交交易哈希集合（供回执查询；内存态、不持久化）。 */
    private final Set<String> submitted = ConcurrentHashMap.newKeySet();

    /** 模拟链 ID（注册表键 = 消息 targetChain）。 */
    private final String chainId;

    /** 交易序号（伪哈希派生输入 + 模拟高度）。 */
    private final AtomicLong txSequence = new AtomicLong();

    /**
     * 构造模拟链适配器。
     *
     * @param chainId 模拟链 ID（如 {@code "L2-sim"}；作为 targetChain 匹配键）
     */
    public SimulatedChainAdapter(String chainId) {
        this.chainId = Objects.requireNonNull(chainId, "chainId");
    }

    @Override
    public String getChainId() {
        return chainId;
    }

    /**
     * 模拟高度：已提交交易数（仅用于冒烟/日志，不代表真实区块高度）。
     */
    @Override
    public long getBlockHeight() {
        return txSequence.get();
    }

    @Override
    public String sendTransaction(byte[] tx) {
        long seq = txSequence.incrementAndGet();
        String hash = deriveHash(tx, seq);
        submitted.add(hash);
        return hash;
    }

    @Override
    public Object getTransactionReceipt(String hash) {
        if (hash == null || !submitted.contains(hash)) {
            return null;
        }
        Map<String, String> receipt = new LinkedHashMap<>();
        receipt.put("status", "0x1");
        receipt.put("txHash", hash);
        return receipt;
    }

    @Override
    public String callContract(String address, String data) {
        return "0x";
    }

    /**
     * 伪 txHash 派生：{@code SHA-256(chainId ‖ 0x00 ‖ tx ‖ seq)}。
     */
    private String deriveHash(byte[] tx, long seq) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(chainId.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            if (tx != null) {
                md.update(tx);
            }
            md.update(ByteBuffer.allocate(Long.BYTES).putLong(seq).array());
            return "0x" + HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 为 JDK 必备算法，正常不可达；兜底返回可读伪哈希（不抛异常）
            return "0xsim-" + chainId + "-" + seq;
        }
    }
}
