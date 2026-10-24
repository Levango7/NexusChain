package org.nexus.sync.validator;

import org.nexus.consensus.finality.net.ValidatorSetCodec;
import org.nexus.consensus.finality.net.ValidatorSetCodec.ValidatorSetMessage;

/**
 * 验证人集消息的签名/重放决策器 —— PLAN-001 签名化扩展【第二切片：接线】。
 *
 * <p>把 {@link ValidatorSetSignatureVerifier}（纯密码学内核）与消息语义（谁的公钥、
 * 水位从哪来）分离：本类是<b>无状态纯函数</b>，水位与公钥查询由调用方提供，
 * 因而可在无 Spring / 无 DB 的单测里逐条断言。</p>
 *
 * <p>决策语义（与方案文档 §2.2 对齐，向后兼容）：</p>
 * <ul>
 *   <li>无签名（v1 老消息）→ {@link Decision#ACCEPT_UNSIGNED}：继续既有幂等注册路径；
 *       不记水位（未签名消息不得推进水位——否则攻击者可用无签名消息把
 *       {@code lastSeen} 推高，阻塞后续合法签名消息）。</li>
 *   <li>有签名 + 验签失败/密钥缺失 → {@link Decision#DROP_BAD_SIGNATURE}：
 *       丢弃（不注册、不中继），仅记录告警——签名存在的唯一意义即"不可验证即不受理"。</li>
 *   <li>有签名 + 验签通过 + {@code nonce <= lastSeen} → {@link Decision#DROP_REPLAY}：丢弃。</li>
 *   <li>有签名 + 验签通过 + nonce 新 → {@link Decision#ACCEPT_SIGNED}：继续注册路径，
 *       由调用方在成功后推进水位。</li>
 * </ul>
 *
 * <p><b>公钥来源</b>：add 消息自带 {@code publicKey}（自签名，防止第三方冒用他人地址，
 * 但不阻止攻击者用自己的密钥注册新地址——注册表的准入边界由质押/治理路径负责，本类不扩权）；
 * remove 消息不带公钥，须由调用方从本地注册表解析。</p>
 *
 * <p><b>重放水位</b>：由调用方聚合（本进程内存 watermark + {@code validators_synced.nonce}），
 * 本类只做纯比较。nonce 相等也视为重放（同一消息重复广播只应生效一次）。</p>
 */
public final class ValidatorSetMessageVerifier {

    public enum Decision {
        ACCEPT_UNSIGNED,
        ACCEPT_SIGNED,
        DROP_BAD_SIGNATURE,
        DROP_REPLAY,
        /** enforce 模式：无签名消息不受理（迁移完成后启用）。 */
        DROP_UNSIGNED
    }

    /** 决策结果（detail 仅用于日志/断言，不参与控制流）。 */
    public record Result(Decision decision, String detail) {
        public boolean accepted() {
            return decision == Decision.ACCEPT_UNSIGNED || decision == Decision.ACCEPT_SIGNED;
        }
    }

    private final ValidatorSetSignatureVerifier crypto = new ValidatorSetSignatureVerifier();

    /**
     * 兼容模式决策（等价 {@code requireSignature=false}）：未签名消息受理。
     */
    public Result decide(ValidatorSetMessage msg, String registeredPublicKeyHex, long lastSeenNonce) {
        return decide(msg, registeredPublicKeyHex, lastSeenNonce, false);
    }

    /**
     * @param msg                    解码后的验证人集消息
     * @param registeredPublicKeyHex remove 消息的公钥来源（本地注册表登记值）；add 消息忽略
     * @param lastSeenNonce          该地址已见最大 nonce（无记录传 0）
     * @param requireSignature       enforce 模式：无签名 → {@link Decision#DROP_UNSIGNED}
     *                               （迁移窗口后启用；注意全表回放消息当前无签名，
     *                               启用前需先解决回放语义——见方案文档边界）
     */
    public Result decide(ValidatorSetMessage msg, String registeredPublicKeyHex,
                         long lastSeenNonce, boolean requireSignature) {
        if (msg == null) {
            return new Result(Decision.DROP_BAD_SIGNATURE, "null message");
        }
        if (!msg.hasSignature()) {
            return requireSignature
                    ? new Result(Decision.DROP_UNSIGNED, "no signature (enforce mode)")
                    : new Result(Decision.ACCEPT_UNSIGNED, "no signature (v1-compatible)");
        }
        String publicKey = msg.isAdd() ? msg.publicKey() : registeredPublicKeyHex;
        if (publicKey == null || publicKey.isEmpty()) {
            return new Result(Decision.DROP_BAD_SIGNATURE,
                    "no public key available for verification (" + msg.action() + ")");
        }
        ValidatorSetSignatureVerifier.SignatureCheckResult check =
                crypto.check(ValidatorSetCodec.canonicalSigningBytes(msg), publicKey, msg.signature());
        if (check != ValidatorSetSignatureVerifier.SignatureCheckResult.VALID) {
            return new Result(Decision.DROP_BAD_SIGNATURE, "signature check=" + check);
        }
        if (crypto.isReplay(msg.nonce(), lastSeenNonce)) {
            return new Result(Decision.DROP_REPLAY,
                    "nonce " + msg.nonce() + " <= lastSeen " + lastSeenNonce);
        }
        return new Result(Decision.ACCEPT_SIGNED, "verified nonce=" + msg.nonce());
    }
}
