package org.nexus.core;

import org.nexus.core.payment.BridgeTransaction;
import org.nexus.core.payment.PaymentChannel;
import org.nexus.core.payment.StableCoinPosition;

import java.util.Collection;

/**
 * Abstraction for payment extension state persistence.
 * Implementations: InMemory (dev), JDBC (production).
 */
public interface PaymentStateStore {

    // --- Payment Channels ---
    void putChannel(String channelId, PaymentChannel channel);
    PaymentChannel getChannel(String channelId);
    Collection<PaymentChannel> getAllChannels();

    // --- StableCoin Positions ---
    void putPosition(String positionId, StableCoinPosition position);
    StableCoinPosition getPosition(String positionId);
    Collection<StableCoinPosition> getAllPositions();

    // --- Bridge Transactions ---
    void putBridgeTx(String bridgeTxId, BridgeTransaction bridgeTx);
    BridgeTransaction getBridgeTx(String bridgeTxId);
    Collection<BridgeTransaction> getAllBridgeTxs();
}