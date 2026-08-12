package org.nexus.consensus.finality.net;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.consensus.finality.FinalityGadget;
import org.nexus.consensus.finality.Vote;
import org.nexus.consensus.pos.StakingServiceImpl;
import org.nexus.consensus.pos.Validator;
import org.nexus.consensus.pos.ValidatorRegistry;
import org.nexus.consensus.pos.ValidatorStatus;
import org.nexus.consensus.pos.StakingService;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FinalityVoteBroadcasterTest {

    private FinalityGadget gadget;
    private FinalityVoteBroadcaster broadcaster;
    private List<FinalityVoteBroadcaster.FinalityVoteBroadcastEvent> events;
    private ApplicationEventPublisher publisher;

    private static final byte[] CP1 = new byte[]{1, 2, 3};

    @BeforeEach
    void setUp() {
        ValidatorRegistry registry = new ValidatorRegistry(new BigDecimal("100"), 100);
        StakingService staking = new StakingServiceImpl();
        try {
            var f = StakingServiceImpl.class.getDeclaredField("validatorRegistry");
            f.setAccessible(true);
            f.set(staking, registry);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        registry.register("v1", "pubkey-v1", new BigDecimal("300"), 0.1);
        Validator v = registry.getValidator("v1");
        v.setStatus(ValidatorStatus.ACTIVE);
        staking.stake("v1", new BigDecimal("300"));

        gadget = new FinalityGadget(registry, staking);
        events = new ArrayList<>();
        publisher = new ApplicationEventPublisher() {
            @Override
            public void publishEvent(Object event) {
                if (event instanceof FinalityVoteBroadcaster.FinalityVoteBroadcastEvent) {
                    events.add((FinalityVoteBroadcaster.FinalityVoteBroadcastEvent) event);
                }
            }
            @Override
            public void publishEvent(org.springframework.context.ApplicationEvent event) {
                publishEvent((Object) event);
            }
        };
        broadcaster = new FinalityVoteBroadcaster(gadget, publisher);
    }

    @Test
    void codecRoundTrip() {
        Vote vote = new Vote(7, CP1, "validator-A", new byte[]{0x01, 0x02});
        byte[] payload = FinalityVoteCodec.encode(vote);
        assertEquals(FinalityVoteCodec.MAGIC, payload[0]);

        Vote decoded = FinalityVoteCodec.decode(payload);
        assertNotNull(decoded);
        assertEquals(vote.getEpoch(), decoded.getEpoch());
        assertArrayEquals(vote.getCheckpointHash(), decoded.getCheckpointHash());
        assertEquals(vote.getValidatorAddress(), decoded.getValidatorAddress());
        assertArrayEquals(vote.getSignature(), decoded.getSignature());
    }

    @Test
    void codecRejectsMalformed() {
        assertNull(FinalityVoteCodec.decode(null));
        assertNull(FinalityVoteCodec.decode(new byte[0]));
        assertNull(FinalityVoteCodec.decode(new byte[]{0x00, 0x01}));
    }

    @Test
    void broadcastEmitsEvent() {
        broadcaster.broadcast(new Vote(1, CP1, "v1", new byte[]{0x55}));
        assertEquals(1, events.size());
        assertEquals(1, events.get(0).getVote().getEpoch());
    }

    @Test
    void onVoteReceivedSubmitsToGadget() {
        // 外部收到一票后应进入 FinalityGadget（权重累积）
        Vote external = new Vote(1, CP1, "v1", new byte[]{0x01});
        broadcaster.onVoteReceived(FinalityVoteCodec.encode(external));

        org.nexus.consensus.finality.FinalityRecord rec = gadget.getFinality(1, CP1);
        assertEquals(new BigDecimal("300"), rec.getVotedWeight());
    }

    @Test
    void broadcasterListenerInterfaceIsInvoked() {
        AtomicInteger outgoing = new AtomicInteger();
        broadcaster.addListener(new FinalityVoteBroadcaster.VoteListener() {
            @Override
            public void onOutgoingVote(Vote vote, byte[] payload) {
                outgoing.incrementAndGet();
            }
        });
        broadcaster.broadcast(new Vote(1, CP1, "v1", new byte[]{0x01}));
        assertEquals(1, outgoing.get());
    }
}
