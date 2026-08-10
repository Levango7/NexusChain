package org.nexus.core;

import org.nexus.core.payment.BridgeTransaction;
import org.nexus.core.payment.PaymentChannel;
import org.nexus.core.payment.StableCoinPosition;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation for development/testing.
 * Data lost on restart. Use JdbcPaymentStateStore in production.
 */
@Component
@Profile("dev")
public class InMemoryPaymentStateStore implements PaymentStateStore {

    private final Map<String, PaymentChannel> channels = new ConcurrentHashMap<>();
    private final Map<String, StableCoinPosition> positions = new ConcurrentHashMap<>();
    private final Map<String, BridgeTransaction> bridgeTxs = new ConcurrentHashMap<>();

    @Override public void putChannel(String id, PaymentChannel ch) { channels.put(id, ch); }
    @Override public PaymentChannel getChannel(String id) { return channels.get(id); }
    @Override public Collection<PaymentChannel> getAllChannels() { return Collections.unmodifiableCollection(channels.values()); }

    @Override public void putPosition(String id, StableCoinPosition pos) { positions.put(id, pos); }
    @Override public StableCoinPosition getPosition(String id) { return positions.get(id); }
    @Override public Collection<StableCoinPosition> getAllPositions() { return Collections.unmodifiableCollection(positions.values()); }

    @Override public void putBridgeTx(String id, BridgeTransaction tx) { bridgeTxs.put(id, tx); }
    @Override public BridgeTransaction getBridgeTx(String id) { return bridgeTxs.get(id); }
    @Override public Collection<BridgeTransaction> getAllBridgeTxs() { return Collections.unmodifiableCollection(bridgeTxs.values()); }
}