package org.nexus.signing.mpc;

import org.bouncycastle.asn1.x9.ECNamedCurveTable;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.math.ec.ECPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.signing.mpc.crypto.AggregateRequest;
import org.nexus.signing.mpc.crypto.AggregateResponse;
import org.nexus.signing.mpc.crypto.DkgRequest;
import org.nexus.signing.mpc.crypto.DkgResponse;
import org.nexus.signing.mpc.crypto.MpcCryptoEngine;
import org.nexus.signing.mpc.crypto.SignRequest;
import org.nexus.signing.mpc.crypto.SignResponse;
import org.nexus.signing.mpc.persistence.MpcKeyShareStore;
import org.nexus.signing.mpc.persistence.MpcSessionRepository;
import org.nexus.signing.mpc.persistence.MpcWalletRepository;
import org.nexus.signing.mpc.router.MessageRouter;
import org.nexus.signing.mpc.transport.MpcMessage;
import org.nexus.signing.mpc.transport.MpcTransport;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultMpcService} 单元测试。
 *
 * <p>Phase 4 任务 #82：MPC 协议编排层单元测试（审计报告第二批 §4.1）。
 * 验证三个核心编排方法（generateKeyShare / sign / aggregateSignature）的
 * 成功路径与失败路径，编排逻辑通过 Mock {@link MpcCryptoEngine} 隔离底层
 * Rust 密码学引擎。</p>
 *
 * <h2>测试矩阵</h2>
 * <ul>
 *   <li>{@link #testGenerateKeyShare_success_savesKeyShare} — DKG 成功：验证 keyShareStore.save 调用与 DkgResult 内容</li>
 *   <li>{@link #testGenerateKeyShare_engineFailure_throwsMpcProtocolException} — DKG 失败：验证抛 MpcProtocolException</li>
 *   <li>{@link #testSign_success_broadcastsPartialSignature} — 签名成功：验证 messageRouter 广播部分签名</li>
 *   <li>{@link #testAggregateSignature_success_ecdsaVerified} — 聚合成功：验证真实 ECDSA 签名通过 BouncyCastle 验证</li>
 * </ul>
 *
 * <p>聚合测试用真实 ECDSA 签名（secp256k1，BouncyCastle），确保
 * {@link DefaultMpcService#verifyEcdsaSignature} 的验证逻辑被真实覆盖。</p>
 *
 * <p>JUnit 4 + Mockito（与 {@code SigningTccActionTest} 一致）。</p>
 */
@ExtendWith(MockitoExtension.class)
public class DefaultMpcServiceTest {

    @Mock
    private MpcCryptoEngine mpcCryptoEngine;

    @Mock
    private MpcSessionRepository sessionRepository;

    @Mock
    private MpcWalletRepository walletRepository;

    @Mock
    private MpcKeyShareStore keyShareStore;

    @Mock
    private MpcTransport transport;

    @Mock
    private MessageRouter messageRouter;

    private DefaultMpcService service;

    @BeforeEach
    public void setUp() {
        service = new DefaultMpcService(mpcCryptoEngine, sessionRepository,
                walletRepository, keyShareStore, transport, messageRouter);
    }

    // ==================== generateKeyShare ====================

    /**
     * DKG 成功路径：Mock 引擎 dkg 返回 success=true，
     * 验证 keyShareStore.save 被调用且 DkgResult 内容正确。
     */
    @Test
    public void testGenerateKeyShare_success_savesKeyShare() {
        String sessionId = "dkg-session-001";
        String participantId = "p1";
        String publicKeyHex = "04aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899"
                + "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899";
        String keyShareHex = "encrypted-share-p1";
        String proofHex = "dkg-proof-p1";

        List<MpcParticipant> participants = List.of(
                new MpcParticipant("p1", "host1:50051", "pkshare1"),
                new MpcParticipant("p2", "host2:50051", "pkshare2")
        );

        DkgResponse mockResp = new DkgResponse(publicKeyHex, keyShareHex, proofHex, true, null);
        when(mpcCryptoEngine.dkg(any(DkgRequest.class))).thenReturn(mockResp);

        MpcKeyGeneration.DkgResult result = service.generateKeyShare(
                sessionId, 2, 2, 0, participantId, "secp256k1", participants);

        // 验证返回的 DkgResult
        assertNotNull(result, "DkgResult should not be null");
        assertEquals(publicKeyHex, result.getJointPublicKeyHex(), "joint public key should match engine response");
        assertEquals(1, result.getShares().size(), "should contain 1 key share");
        assertEquals(participantId, result.getShares().get(0).getParticipantId(), "share owner should be p1");

        // 验证 keyShareStore.save 被调用且内容正确
        ArgumentCaptor<MpcKeyShare> captor = ArgumentCaptor.forClass(MpcKeyShare.class);
        verify(keyShareStore).save(captor.capture());
        MpcKeyShare saved = captor.getValue();
        assertEquals(participantId, saved.getParticipantId(), "saved share owner");
        assertEquals(keyShareHex, saved.getPrivateShareHex(), "saved private share (encrypted)");
        assertEquals(publicKeyHex, saved.getPublicShareHex(), "saved public share (joint public key)");

        // 验证引擎被调用
        verify(mpcCryptoEngine).dkg(any(DkgRequest.class));
    }

    /**
     * DKG 失败路径：Mock 引擎 dkg 返回 success=false，
     * 验证抛 {@link MpcProtocolException}。
     */
    @Test
    public void testGenerateKeyShare_engineFailure_throwsMpcProtocolException() { assertThrows(MpcProtocolException.class, () -> {
        String sessionId = "dkg-session-fail";
        String participantId = "p1";

        List<MpcParticipant> participants = List.of(
                new MpcParticipant("p1", "host1:50051", "pkshare1"),
                new MpcParticipant("p2", "host2:50051", "pkshare2")
        );

        DkgResponse mockResp = new DkgResponse(null, null, null, false, "engine process down");
        when(mpcCryptoEngine.dkg(any(DkgRequest.class))).thenReturn(mockResp);

        service.generateKeyShare(sessionId, 2, 2, 0, participantId, "secp256k1", participants);
        });
    }

    // ==================== sign ====================

    /**
     * 签名成功路径：Mock 引擎 sign 返回 success=true，
     * 验证 {@link MessageRouter#broadcast} 被调用（广播部分签名给其他参与方）。
     */
    @Test
    public void testSign_success_broadcastsPartialSignature() {
        String sessionId = "sign-session-001";
        String walletId = "wallet-001";
        String publicKeyHex = "04aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899"
                + "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899";
        // 32 字节消息哈希（hex 64 字符）
        String messageHashHex = "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899";
        String participantId = "p1";
        String partialSig = "partial-signature-p1";

        List<MpcParticipant> participants = List.of(
                new MpcParticipant("p1", "host1:50051", "pkshare1"),
                new MpcParticipant("p2", "host2:50051", "pkshare2")
        );

        // Mock：加载本节点密钥份额
        MpcKeyShare keyShare = new MpcKeyShare(participantId, "priv-p1", "pub-p1", null);
        when(keyShareStore.load(participantId)).thenReturn(Optional.of(keyShare));

        // Mock：传输层已连接（跳过 connect）
        when(transport.isConnected()).thenReturn(true);

        // Mock：引擎签名成功
        SignResponse mockResp = new SignResponse(partialSig, "sign-proof-p1", true, null);
        when(mpcCryptoEngine.sign(any(SignRequest.class))).thenReturn(mockResp);

        // threshold=1 → RoundBarrier 立即满足
        String result = service.sign(sessionId, walletId, publicKeyHex, messageHashHex,
                0, participantId, participants, 1, 1000L);

        // 验证返回本节点部分签名
        assertEquals(partialSig, result, "returned partial signature should match engine response");

        // 验证 messageRouter 广播了部分签名（transport 广播经由 MessageRouter）
        verify(messageRouter).broadcast(any(MpcMessage.class));

        // 验证引擎被调用
        verify(mpcCryptoEngine).sign(any(SignRequest.class));

        // 验证密钥份额被加载
        verify(keyShareStore).load(participantId);
    }

    // ==================== aggregateSignature ====================

    /**
     * 聚合成功路径：Mock 引擎 aggregate 返回 success=true，
     * 用真实 ECDSA 签名（secp256k1，BouncyCastle）确保
     * {@link DefaultMpcService#verifyEcdsaSignature} 验证通过。
     */
    @Test
    public void testAggregateSignature_success_ecdsaVerified() {
        // 生成真实 ECDSA 签名（secp256k1）
        String[] ecdsa = generateEcdsaSignature();
        String publicKeyHex = ecdsa[0];
        String messageHashHex = ecdsa[1];
        String rHex = ecdsa[2];
        String sHex = ecdsa[3];
        String signatureHex = rHex + sHex;

        String sessionId = "agg-session-001";
        String walletId = "wallet-001";
        List<String> partialSignatures = List.of("partial-sig-1", "partial-sig-2");

        // Mock：引擎聚合成功，返回真实 ECDSA (r, s)
        AggregateResponse mockResp = new AggregateResponse(
                signatureHex, rHex, sHex, 0, true, null);
        when(mpcCryptoEngine.aggregate(any(AggregateRequest.class))).thenReturn(mockResp);

        // sessionRepository.findById 默认返回 Optional.empty() → 创建新 session
        AggregateResponse result = service.aggregateSignature(
                sessionId, walletId, publicKeyHex, messageHashHex, partialSignatures);

        // 验证返回的聚合响应
        assertNotNull(result, "aggregate response should not be null");
        assertTrue(result.isSuccess(), "response should be success");
        assertEquals(rHex, result.getR(), "r should match");
        assertEquals(sHex, result.getS(), "s should match");
        assertEquals(signatureHex, result.getSignature(), "signature (r||s) should match");

        // 验证引擎被调用
        verify(mpcCryptoEngine).aggregate(any(AggregateRequest.class));

        // 验证最终签名被广播给所有参与方
        verify(messageRouter).broadcast(any(MpcMessage.class));
    }

    // ==================== 辅助方法 ====================

    /**
     * 生成真实 ECDSA 签名（secp256k1），用于 aggregateSignature 测试。
     *
     * <p>签名流程（与 {@link DefaultMpcService#verifyEcdsaSignature} 验证逻辑对应）：</p>
     * <ol>
     *   <li>私钥 d，公钥 Q = d*G</li>
     *   <li>随机数 k，R = k*G，r = R.x mod n</li>
     *   <li>s = k^-1 * (z + r*d) mod n</li>
     * </ol>
     *
     * <p>固定 d/k/z 值确保测试可重复。</p>
     *
     * @return 长度 4 的数组：[0]=publicKeyHex(未压缩 04||x||y),
     *         [1]=messageHashHex(32B), [2]=rHex(32B), [3]=sHex(32B)
     */
    private static String[] generateEcdsaSignature() {
        X9ECParameters params = ECNamedCurveTable.getByName("secp256k1");
        assertNotNull(params, "secp256k1 curve must be available in BouncyCastle");
        ECPoint G = params.getG();
        BigInteger n = params.getN();

        // 固定值（测试可重复），均在 [1, n-1] 范围内
        BigInteger d = new BigInteger(
                "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90", 16).mod(n);
        BigInteger k = new BigInteger(
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", 16).mod(n);
        BigInteger z = new BigInteger(
                "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210", 16);

        // 确保 d, k 在 [1, n-1]（mod n 后极小概率为 0，防御性处理）
        if (d.signum() <= 0) {
            d = d.add(BigInteger.ONE);
        }
        if (k.signum() <= 0) {
            k = k.add(BigInteger.ONE);
        }

        // 公钥 Q = d*G（未压缩编码 04||x||y）
        ECPoint Q = G.multiply(d).normalize();
        byte[] pubBytes = Q.getEncoded(false);
        String publicKeyHex = HexFormat.of().formatHex(pubBytes);

        // 消息哈希 z（32 字节 hex）
        String messageHashHex = toFixedHex(z, 32);

        // ECDSA 签名：R = k*G, r = R.x mod n, s = k^-1 * (z + r*d) mod n
        ECPoint R = G.multiply(k).normalize();
        BigInteger r = R.getAffineXCoord().toBigInteger().mod(n);
        BigInteger kInv = k.modInverse(n);
        BigInteger s = kInv.multiply(z.add(r.multiply(d))).mod(n);

        String rHex = toFixedHex(r, 32);
        String sHex = toFixedHex(s, 32);

        return new String[]{publicKeyHex, messageHashHex, rHex, sHex};
    }

    /**
     * 把 {@link BigInteger} 转为固定字节长度的 hex 字符串（左侧补零）。
     *
     * @param value   非负 BigInteger
     * @param byteLen 目标字节数
     * @return hex 字符串（长度 = 2 * byteLen）
     */
    private static String toFixedHex(BigInteger value, int byteLen) {
        byte[] bytes = value.toByteArray();
        // BigInteger.toByteArray() 对正数可能带 1 个前导 0 字节（符号位），去掉
        int off = (bytes.length > 1 && bytes[0] == 0) ? 1 : 0;
        int srcLen = bytes.length - off;
        if (srcLen > byteLen) {
            throw new IllegalArgumentException(
                    "value too large for " + byteLen + " bytes (needs " + srcLen + ")");
        }
        byte[] fixed = new byte[byteLen];
        System.arraycopy(bytes, off, fixed, byteLen - srcLen, srcLen);
        return HexFormat.of().formatHex(fixed);
    }
}