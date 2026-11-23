package org.nexus.payment;

import org.junit.jupiter.api.Test;
import org.nexus.core.payment.ChannelManager;
import org.nexus.core.payment.PaymentChannel;
import org.nexus.crypto.ed25519.Ed25519;
import org.nexus.crypto.ed25519.Ed25519KeyPair;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ChannelManagerMonetaryTest {

    @Test
    void recipientBalanceOverflowFailsClosed() {
        ChannelManager manager = new ChannelManager();
        PaymentChannel channel = manager.openChannel("alice", "bob", 2L, 100);
        Ed25519KeyPair alice = Ed25519.generateKeyPair();
        Ed25519KeyPair bob = Ed25519.generateKeyPair();
        ReflectionTestUtils.setField(channel, "balance2", Long.MAX_VALUE);
        assertThrows(ArithmeticException.class, () -> manager.initiatePayment(
                channel.getChannelId(), 1L,
                alice.getPrivateKey().getEncoded(), alice.getPublicKey().getEncoded(),
                bob.getPublicKey().getEncoded()));
    }
}
