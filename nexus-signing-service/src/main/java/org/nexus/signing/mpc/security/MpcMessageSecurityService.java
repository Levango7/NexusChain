package org.nexus.signing.mpc.security;

import org.nexus.signing.mpc.MpcProtocolException;
import org.nexus.signing.mpc.transport.MpcMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * MPC 消息安全服务：整合 mTLS + HMAC-SHA256 + Nonce/Timestamp 防重放。
 *
 * <p>该组件是传输安全层的统一入口，对每条出站消息签名、对每条入站消息
 * 验证。三层防御：</p>
 * <ol>
 *   <li><b>mTLS</b>（{@link MutualTlsContext}）：双向认证，确保对端身份可信。
 *       在传输层建立时一次性完成，本类不直接介入每条消息。</li>
 *   <li><b>HMAC-SHA256</b>（{@link HmacSigner}）：应用层消息完整性，
 *       防止 TLS 终止于反向代理后的篡改。</li>
 *   <li><b>Nonce + Timestamp</b>（{@link NonceTracker}）：防重放，
 *       拒绝过期与重复消息。</li>
 * </ol>
 *
 * <p><b>使用方式</b>：</p>
 * <pre>
 *   // 出站
 *   MpcMessage out = ...;
 *   MpcMessage secured = securityService.secureOutbound(out);
 *   transport.send(secured);
 *
 *   // 入站
 *   MpcMessage received = transport.receive(...);
 *   MpcMessage verified = securityService.verifyInbound(received);  // 抛异常若失败
 * </pre>
 */
@Component
public class MpcMessageSecurityService {

    private static final Logger log = LoggerFactory.getLogger(MpcMessageSecurityService.class);

    private final HmacSigner hmacSigner;
    private final NonceTracker nonceTracker;
    private final MutualTlsContext tlsContext;

    /**
     * 构造安全服务。
     *
     * <p>当 {@code nexus.mpc.security.enabled=false} 时，HMAC 与 Nonce 检查
     * 被禁用（仅用于本地测试）。mTLS 上下文始终可选（仅在网络层使用）。</p>
     *
     * @param hmacSigner   HMAC 签名器（可选，禁用时传 null）
     * @param nonceTracker Nonce 跟踪器（可选，禁用时传 null）
     * @param tlsContext   mTLS 上下文（可选）
     * @param securityEnabled 是否启用应用层安全（HMAC + Nonce）
     */
    public MpcMessageSecurityService(
            @Autowired(required = false) HmacSigner hmacSigner,
            @Autowired(required = false) NonceTracker nonceTracker,
            @Autowired(required = false) MutualTlsContext tlsContext,
            @Value("${nexus.mpc.security.enabled:true}") boolean securityEnabled) {
        this.hmacSigner = hmacSigner;
        this.nonceTracker = nonceTracker;
        this.tlsContext = tlsContext;
        this.securityEnabled = securityEnabled;
        if (!securityEnabled) {
            log.warn("MPC security layer DISABLED (nexus.mpc.security.enabled=false) — only for local testing");
        }
    }

    private final boolean securityEnabled;

    /**
     * 对出站消息签名（填充 hmacHex）。
     *
     * @param message 原始消息
     * @return 带 HMAC 的消息副本
     */
    public MpcMessage secureOutbound(MpcMessage message) {
        if (!securityEnabled || hmacSigner == null) {
            return message;
        }
        String hmac = hmacSigner.sign(
                message.getMessageId(),
                message.getSessionId(),
                message.getRound(),
                message.getType().name(),
                message.getFromParticipantId(),
                message.getToParticipantId(),
                message.getPayloadHex(),
                message.getTimestamp(),
                message.getNonce());
        return message.withHmac(hmac);
    }

    /**
     * 验证入站消息：HMAC + Nonce + Timestamp。
     *
     * @param message 接收到的消息
     * @return 验证通过的消息（原对象）
     * @throws MpcProtocolException 若验证失败
     */
    public MpcMessage verifyInbound(MpcMessage message) {
        if (!securityEnabled) {
            return message;
        }
        // 1. HMAC 验证
        if (hmacSigner != null) {
            if (message.getHmacHex() == null) {
                throw new MpcProtocolException(
                        MpcProtocolException.Reason.SHARE_VERIFICATION_FAILED,
                        "missing HMAC on inbound message: " + message.getMessageId());
            }
            String expected = hmacSigner.sign(
                    message.getMessageId(),
                    message.getSessionId(),
                    message.getRound(),
                    message.getType().name(),
                    message.getFromParticipantId(),
                    message.getToParticipantId(),
                    message.getPayloadHex(),
                    message.getTimestamp(),
                    message.getNonce());
            if (!java.security.MessageDigest.isEqual(
                    fromHex(expected), fromHex(message.getHmacHex()))) {
                throw new MpcProtocolException(
                        MpcProtocolException.Reason.SHARE_VERIFICATION_FAILED,
                        "HMAC verification failed: " + message.getMessageId(),
                        message.getFromParticipantId());
            }
        }
        // 2. Nonce + Timestamp 验证
        if (nonceTracker != null) {
            if (!nonceTracker.checkAndRecord(
                    message.getFromParticipantId(),
                    message.getNonce(),
                    message.getTimestamp())) {
                throw new MpcProtocolException(
                        MpcProtocolException.Reason.SHARE_VERIFICATION_FAILED,
                        "replay check failed: " + message.getMessageId(),
                        message.getFromParticipantId());
            }
        }
        log.debug("Inbound message verified: {}", message.getMessageId());
        return message;
    }

    /**
     * @return mTLS 上下文（可能为 null，仅传输层使用）
     */
    public MutualTlsContext getTlsContext() {
        return tlsContext;
    }

    /**
     * @return 是否启用应用层安全
     */
    public boolean isSecurityEnabled() {
        return securityEnabled;
    }

    private static byte[] fromHex(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}