package org.nexus.sync;

import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.Test;
import org.nexus.consensus.pos.PosConsensusEngine;
import org.nexus.consensus.pos.ValidatorRegistry;
import org.nexus.core.Block;
import org.nexus.core.account.Transaction;
import org.nexus.keystore.crypto.KeyPair;
import org.nexus.keystore.crypto.ed25519.Ed25519DsaSigner;
import org.nexus.keystore.crypto.ed25519.Signature;
import org.nexus.keystore.wallet.KeystoreAction;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2P 区块编解码必须完整保留 PoS 签名分量。
 *
 * <p>回归背景：P2P 的 {@code NexusChain.proto} 曾缺少 {@code block_notice}
 * 字段，{@link Utils#encodeBlock}/{@link Utils#parseBlock} 也未透传，
 * 导致接收端把签名 S 分量还原为空数组，所有跨节点区块验签必然失败
 * （单节点因为本地不验签而看不出来）。本测试用真实 Ed25519 签名走一遍
 * 编码 → 解码 → 验签链路。</p>
 */
class UtilsBlockSignatureRoundTripTest {

    private static final String VALIDATOR_ADDRESS = "nv3gC5mGzFjtv2ouzw3Yd3vQYbfys3tD8";

    @Test
    void signedBlockSurvivesP2pRoundTripAndStillVerifies() throws Exception {
        KeyPair keyPair = KeyPair.generateEd25519KeyPair();
        ValidatorRegistry registry = new ValidatorRegistry();
        assertTrue(registry.register(
                VALIDATOR_ADDRESS,
                Hex.encodeHexString(keyPair.getPublicKey().getBytes()),
                BigDecimal.valueOf(1000),
                0.1));

        PosConsensusEngine engine = new PosConsensusEngine(keyPair, 30);
        Field registryField = PosConsensusEngine.class.getDeclaredField("validatorRegistry");
        registryField.setAccessible(true);
        registryField.set(engine, registry);

        Block block = new Block();
        block.nVersion = 1;
        block.hashPrevBlock = new byte[Block.HASH_SIZE];
        block.hashMerkleRoot = new byte[Block.HASH_SIZE];
        block.hashMerkleState = new byte[Block.HASH_SIZE];
        block.hashMerkleIncubate = new byte[Block.HASH_SIZE];
        block.nHeight = 1;
        block.nTime = System.currentTimeMillis() / 1000;
        block.nBits = new byte[Block.HASH_SIZE];

        Transaction coinbase = new Transaction();
        coinbase.from = new byte[Transaction.PUBLIC_KEY_SIZE];
        coinbase.to = KeystoreAction.addressToPubkeyHash(VALIDATOR_ADDRESS);
        coinbase.signature = new byte[Transaction.SIGNATURE_SIZE];
        block.body = Collections.singletonList(coinbase);

        Method signingData = PosConsensusEngine.class.getDeclaredMethod("getSigningData", Block.class);
        signingData.setAccessible(true);
        byte[] payload = (byte[]) signingData.invoke(engine, block);
        Signature signature = new Ed25519DsaSigner(keyPair.getPrivateKey()).sign(payload);
        block.nNonce = signature.getBinaryR();
        block.blockNotice = signature.getBinaryS();

        // 模拟真实 P2P 路径：发送端 encodeBlock → 接收端 parseBlock
        Block received = Utils.parseBlock(Utils.encodeBlock(block));

        assertArrayEquals(block.nNonce, received.nNonce, "nNonce(R) 必须完整透传");
        assertArrayEquals(block.blockNotice, received.blockNotice, "blockNotice(S) 必须完整透传");
        assertTrue(engine.verifyBlockSignature(received),
                "经过 P2P 编解码后的区块仍应通过提案者公钥验签");

        // 篡改 S 分量必须验签失败（fail-closed）
        received.blockNotice[0] ^= 0x01;
        assertFalse(engine.verifyBlockSignature(received));
    }
}
