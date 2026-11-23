package org.nexus.core.payment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class PaymentChannelMonetaryTest {

    @Test
    void updateRejectsOverflowInConservationArithmetic() {
        PaymentChannel channel = new PaymentChannel();
        channel.setBalance1(Long.MAX_VALUE);
        channel.setBalance2(1L);
        channel.setNonce(0L);
        channel.setState(PaymentChannel.State.OPEN);
        assertThrows(ArithmeticException.class, () -> channel.update(1L, Long.MAX_VALUE, 1L));
    }
}
