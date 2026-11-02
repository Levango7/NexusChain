package org.nexus.p2p;

import com.google.protobuf.AbstractMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.nexus.consensus.finality.FinalityGadget;
import org.nexus.consensus.finality.FinalityRecord;
import org.nexus.consensus.finality.Vote;
import org.nexus.consensus.finality.net.FinalityVoteBroadcaster;
import org.nexus.consensus.finality.net.FinalityVoteP2PCodec;
import org.nexus.consensus.pos.StakingServiceImpl;
import org.nexus.consensus.pos.Validator;
import org.nexus.consensus.pos.ValidatorRegistry;
import org.nexus.consensus.pos.ValidatorStatus;
import org.nexus.sync.SyncManager;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * PLAN-015 Phase 6A：最终性投票 P2P 发送 → 接收完整往返测试。
 *
 * <p>补齐 roadmap-unresolved #26：此前测试只覆盖
 * {@link FinalityVoteBroadcaster#broadcast(Vote)} 的事件总线和
 * {@code onVoteReceived} 的直接调用，没有测试触达
 * {@code sendOverP2P()} 与 {@code SyncManager.onTransactions()}。
 * 本测试让 A 节点真实构造 {@code Transactions(VOTE)} 消息，经 {@link PeerServer}
 * 捕获后交给 B 节点的 {@link SyncManager#onMessage(Context, PeerServer)}，
 * 断言投票最终进入 B 的 {@link FinalityGadget} 并完成最终化。</p>
 */
class FinalityVoteP2PRoundTripTest {

    private static final byte[] CHECKPOINT =
            java.util.Arrays.copyOf(new byte[]{0x11, 0x22, 0x33}, org.nexus.core.Block.HASH_SIZE);
    private static final long EPOCH = 7L;

    @Test
    void voteBroadcastOverP2PIsConsumedByPeerSyncManager() throws Exception {
        org.nexus.crypto.ed25519.Ed25519KeyPair keyPair =
                org.nexus.crypto.ed25519.Ed25519.generateKeyPair();
        String validatorAddress = "validator-p2p";
        String publicKeyHex = org.apache.commons.codec.binary.Hex.encodeHexString(
                keyPair.getPublicKey().getEncoded());

        // 两个节点各自持有同一验证人的注册表/质押账本；总权重 300，单票即 100%。
        FinalityGadget gadgetA = newGadget(validatorAddress, publicKeyHex);
        FinalityGadget gadgetB = newGadget(validatorAddress, publicKeyHex);

        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        PeerServer peerServer = mock(PeerServer.class);
        FinalityVoteBroadcaster broadcasterA =
                new FinalityVoteBroadcaster(gadgetA, publisher, peerServer);
        FinalityVoteBroadcaster broadcasterB =
                new FinalityVoteBroadcaster(gadgetB, publisher, null);

        Vote vote = signedVote(keyPair, validatorAddress);
        broadcasterA.broadcast(vote);

        // 发送侧：必须真的走到 PeerServer.broadcast，并封装为 Transactions(VOTE)。
        ArgumentCaptor<AbstractMessage> captor = ArgumentCaptor.forClass(AbstractMessage.class);
        verify(peerServer).broadcast(captor.capture());
        NexusChainOuterClass.Transactions transactions =
                assertInstanceOf(NexusChainOuterClass.Transactions.class, captor.getValue());
        assertEquals(1, transactions.getTransactionsCount());
        NexusChainOuterClass.Transaction tx = transactions.getTransactions(0);
        assertEquals(NexusChainOuterClass.TransactionType.VOTE, tx.getTransactionType());
        byte[] payload = tx.getPayload().toByteArray();
        assertTrue(FinalityVoteP2PCodec.isVotePayload(payload),
                "P2P 载荷必须带最终性投票魔数");

        // 接收侧：构造真实 TRANSACTIONS Message → SyncManager.onTransactions 分流。
        NexusChainOuterClass.Message message = NexusChainOuterClass.Message.newBuilder()
                .setCode(NexusChainOuterClass.Code.TRANSACTIONS)
                .setRemotePeer("nexus://" + publicKeyHex + "@127.0.0.1:9235")
                .setBody(transactions.toByteString())
                .build();
        Context context = new Context();
        context.payload = new Payload(message);

        SyncManager syncManager = new SyncManager();
        injectBroadcaster(syncManager, broadcasterB);
        syncManager.onMessage(context, null);

        FinalityRecord record = gadgetB.getFinality(EPOCH, CHECKPOINT);
        assertTrue(record.isFinalized(), "B 节点应通过 P2P 投票最终化检查点");
        assertEquals(new BigDecimal("300"), record.getVotedWeight());
    }

    private FinalityGadget newGadget(String address, String publicKeyHex) {
        ValidatorRegistry registry = new ValidatorRegistry(new BigDecimal("100"), 100);
        StakingServiceImpl staking = new StakingServiceImpl();
        try {
            Field field = StakingServiceImpl.class.getDeclaredField("validatorRegistry");
            field.setAccessible(true);
            field.set(staking, registry);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        registry.register(address, publicKeyHex, new BigDecimal("300"), 0.1);
        Validator validator = registry.getValidator(address);
        validator.setStatus(ValidatorStatus.ACTIVE);
        staking.stake(address, new BigDecimal("300"));
        return new FinalityGadget(registry, staking);
    }

    private Vote signedVote(org.nexus.crypto.ed25519.Ed25519KeyPair keyPair, String address) {
        byte[] payload = ByteBuffer.allocate(8 + CHECKPOINT.length)
                .putLong(EPOCH).put(CHECKPOINT).array();
        try {
            byte[] signature = keyPair.getPrivateKey().sign(payload);
            return new Vote(EPOCH, CHECKPOINT, address, signature,
                    keyPair.getPublicKey().getEncoded());
        } catch (Exception e) {
            throw new RuntimeException("Ed25519 signing failed", e);
        }
    }

    private void injectBroadcaster(SyncManager syncManager, FinalityVoteBroadcaster broadcaster) {
        try {
            Field field = SyncManager.class.getDeclaredField("finalityVoteBroadcaster");
            field.setAccessible(true);
            field.set(syncManager, broadcaster);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
