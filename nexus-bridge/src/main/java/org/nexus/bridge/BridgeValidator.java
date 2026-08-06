package org.nexus.bridge;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 桥验证者接口。
 *
 * <p>桥验证者负责对跨链操作进行签名确认，实现 N-of-M 多签安全机制。
 * 验证者集合由 nexus-consortium 共识层治理产生，桥模块通过本接口
 * 与验证者进行交互。</p>
 *
 * <h2>验证者职责</h2>
 * <ul>
 *   <li>验证源链锁定交易的真实性</li>
 *   <li>对合法的跨链操作提交签名</li>
 *   <li>监控桥状态，必要时触发暂停</li>
 *   <li>参与紧急事件响应</li>
 * </ul>
 *
 * <h2>多签机制</h2>
 * <p>跨链操作需要收集到阈值数量的验证者签名后才能执行。
 * 阈值参数由 {@link BridgeConfig} 配置。</p>
 *
 * <p><b>安全约束（2026-08-06 修复）</b>：仅统计签名数量不足以保证安全——
 * 签名必须逐一对验证者公钥验签（Ed25519），且签名者 ID 必须位于
 * {@link BridgeConfig#getValidatorPublicKeys()} 白名单中。
 * 调用方必须先用 {@link #verifySignature(String, String, String, BridgeConfig)}
 * 或 {@link #filterValidSignatures(String, Map, BridgeConfig)} 完成验签，
 * 再对通过验证的签名集合使用 {@link #meetsThreshold(Set, BridgeConfig)}
 * 做阈值判断。</p>
 *
 * <h2>公钥格式约定</h2>
 * <p>{@code validatorPublicKeys} 中每个条目是签名者 ID 与验证公钥的合体：
 * 条目本身即该验证者的 <b>Ed25519 公钥的 X.509 DER 十六进制编码</b>
 * （与 {@code FileKeyVault#getPublicKey(String)} 返回格式一致），
 * 同时该十六进制串作为签名者 ID 出现在签名映射的 key 中。
 * 验签时以签名者 ID 在白名单中检索，命中后直接用该条目解码为公钥验签。</p>
 *
 * @since 1.0.0
 */
public interface BridgeValidator {

    /**
     * 获取验证者唯一标识（通常为验证者公钥地址）。
     *
     * @return 验证者 ID
     */
    String getValidatorId();

    /**
     * 获取验证者公钥，用于签名验证。
     *
     * @return 验证者公钥（十六进制格式）
     */
    String getPublicKey();

    /**
     * 对跨链操作数据进行签名。
     *
     * <p>签名内容通常包括：源链 ID、目标链 ID、交易金额、
     * 用户地址、时间戳等关键字段的哈希摘要。</p>
     *
     * @param payload 待签名的跨链操作数据
     * @return 签名结果（十六进制格式）
     */
    String sign(byte[] payload);

    /**
     * 验证签名是否来自该验证者。
     *
     * @param payload   原始数据
     * @param signature 待验证的签名
     * @return 如果签名有效返回 {@code true}，否则返回 {@code false}
     */
    boolean verify(byte[] payload, String signature);

    /**
     * 判断该验证者是否处于活跃状态。
     *
     * <p>非活跃验证者的签名将被拒绝。验证者可能因为
     * 节点离线、被治理移除等原因变为非活跃状态。</p>
     *
     * @return 活跃返回 {@code true}，否则返回 {@code false}
     */
    boolean isActive();

    /**
     * 获取验证者的权重。
     *
     * <p>在加权多签场景下，不同验证者可能拥有不同权重。
     * 默认权重为 1，所有验证者权重相等。</p>
     *
     * @return 验证者权重
     */
    default int getWeight() {
        return 1;
    }

    /**
     * 判断给定的验证者集合是否达到多签阈值。
     *
     * <p><b>注意</b>：本方法只做数量检查，<b>不验证签名内容</b>。
     * 调用方必须先将签名集合经
     * {@link #filterValidSignatures(String, Map, BridgeConfig)} 过滤，
     * 只对验签通过的签名者 ID 调用本方法。</p>
     *
     * @param validatorIds 参与签名的验证者 ID 集合（应为已验签通过的集合）
     * @param config       桥配置（包含阈值信息）
     * @return 达到阈值返回 {@code true}，否则返回 {@code false}
     */
    static boolean meetsThreshold(Set<String> validatorIds, BridgeConfig config) {
        if (validatorIds == null || validatorIds.isEmpty() || config == null) {
            return false;
        }
        return validatorIds.size() >= config.getSignatureThreshold();
    }

    /**
     * 构造跨链操作的确定性签名载荷。
     *
     * <p>载荷为规范化字符串 {@code chainId|txId|amount|targetAddress|timestamp}
     * 的 SHA-256 十六进制摘要。签名者与桥服务必须对同一操作构造出
     * 完全相同的载荷，任何字段不一致都会导致验签失败。</p>
     *
     * @param chainId       被确认的链上事件所在链 ID
     * @param txId          桥交易 ID
     * @param amount        跨链金额（NEX 最小单位）
     * @param targetAddress 目标地址（可空，空串参与哈希）
     * @param timestamp     请求时间戳（毫秒）
     * @return 载荷哈希（64 位十六进制）
     */
    static String buildPayload(String chainId, String txId, long amount, String targetAddress, long timestamp) {
        String canonical = (chainId == null ? "" : chainId)
                + "|" + (txId == null ? "" : txId)
                + "|" + amount
                + "|" + (targetAddress == null ? "" : targetAddress)
                + "|" + timestamp;
        return sha256Hex(canonical);
    }

    /**
     * 验证单个签名者对其跨链操作的签名。
     *
     * <p>验签通过需同时满足：</p>
     * <ol>
     *   <li>签名者 ID 存在于 {@code config.validatorPublicKeys} 白名单中；</li>
     *   <li>白名单中对应条目可解码为 Ed25519 公钥；</li>
     *   <li>签名是 Ed25519 算法下对 {@code payload} 的有效签名。</li>
     * </ol>
     *
     * <p>任何异常（公钥格式非法、签名格式非法、算法不可用等）一律按
     * <b>fail-closed</b> 处理返回 {@code false}。</p>
     *
     * @param payload      待验证的载荷（由 {@link #buildPayload} 生成）
     * @param validatorId  签名者 ID（须在白名单中）
     * @param signatureHex 签名的十六进制编码
     * @param config       桥配置（含验证者公钥白名单）
     * @return 签名有效且签名者受信返回 {@code true}，否则返回 {@code false}
     */
    static boolean verifySignature(String payload, String validatorId, String signatureHex, BridgeConfig config) {
        if (payload == null || validatorId == null || signatureHex == null || config == null
                || config.getValidatorPublicKeys() == null) {
            return false;
        }
        if (!config.getValidatorPublicKeys().contains(validatorId)) {
            return false; // 签名者不在白名单
        }
        try {
            PublicKey publicKey = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(hexToBytes(validatorId)));
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(payload.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(hexToBytes(signatureHex));
        } catch (Exception e) {
            return false; // fail-closed：任何解码/验签异常均视为无效
        }
    }

    /**
     * 过滤出所有验签通过且在白名单内的签名者。
     *
     * <p>对 {@code signatures}（验证者 ID → 签名十六进制）逐条调用
     * {@link #verifySignature(String, String, String, BridgeConfig)}，
     * 返回验签通过的签名者 ID 集合。调用方随后应对该集合执行
     * {@link #meetsThreshold(Set, BridgeConfig)} 阈值判断。</p>
     *
     * @param payload    待验证的载荷
     * @param signatures 签名集合（验证者 ID → 签名十六进制），可为 {@code null}
     * @param config     桥配置
     * @return 验签通过的签名者 ID 集合（永不为 {@code null}）
     */
    static Set<String> filterValidSignatures(String payload, Map<String, String> signatures, BridgeConfig config) {
        if (signatures == null || signatures.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> valid = new HashSet<>();
        for (Map.Entry<String, String> entry : signatures.entrySet()) {
            if (verifySignature(payload, entry.getKey(), entry.getValue(), config)) {
                valid.add(entry.getKey());
            }
        }
        return valid;
    }

    /**
     * 将签名集合规整为不可变快照（null 安全）。
     *
     * @param signatures 原始签名集合
     * @return 不可变副本
     */
    static Map<String, String> snapshot(Map<String, String> signatures) {
        return signatures == null ? Collections.emptyMap() : Collections.unmodifiableMap(new HashMap<>(signatures));
    }

    /**
     * 计算 UTF-8 字符串的 SHA-256 十六进制摘要。
     */
    static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return bytesToHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * 字节数组转小写十六进制。
     */
    static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * 十六进制字符串转字节数组。
     */
    static byte[] hexToBytes(String hex) {
        int len = hex.length();
        if ((len & 1) != 0) {
            throw new IllegalArgumentException("Odd-length hex string");
        }
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int hi = Character.digit(hex.charAt(i), 16);
            int lo = Character.digit(hex.charAt(i + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("Non-hex character at index " + i);
            }
            out[i / 2] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
