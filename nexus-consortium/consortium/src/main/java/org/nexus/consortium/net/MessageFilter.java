package org.nexus.consortium.net;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;
import org.nexus.crypto.ed25519.Ed25519PublicKey;

@Slf4j
public class MessageFilter implements Plugin {
    private Cache<String, Boolean> cache;

    public MessageFilter(PeerServerConfig config) {
        this.cache = CacheBuilder.newBuilder()
                .maximumSize(config.getMaxPeers() * 2).build();
    }

    @Override
    public void onMessage(ContextImpl context, ProtoPeerServer server) {
        // filter invalid signatures
        if (!new Ed25519PublicKey(context.getRemote().getID().getBytes()).verify(
                Util.getRawForSign(context.message), context.message.getSignature().toByteArray()
        )) {
            log.error("invalid signature received from " + context.remote);
            context.exit();
            return;
        }
        // reject blocked peer
        if (server.getClient().peersCache.hasBlocked(context.remote)){
            log.error("the peer " + context.remote + " has been blocked");
            context.disconnect();
            return;
        }
        // filter message from your self
        if (context.getRemote().equals(server.getSelf())) {
            log.error("message received from yourself");
            context.exit();
            return;
        }
        // filter message which ttl < 0
        if (context.message.getTtl() < 0) {
            log.error("receive message ttl less than 0");
            context.exit();
            return;
        }
        String k = Hex.encodeHexString(context.message.getSignature().toByteArray());
        // filter message had been received
        if (cache.asMap().containsKey(k)) {
            context.exit();
        }
        log.info("receive " + context.message.getCode()
                + " from " +
                context.remote.getHost() + ":" + context.remote.getPort()
        );
        cache.put(k, true);
    }

    @Override
    public void onStart(ProtoPeerServer server) {

    }

    @Override
    public void onNewPeer(PeerImpl peer, ProtoPeerServer server) {

    }

    @Override
    public void onDisconnect(PeerImpl peer, ProtoPeerServer server) {

    }
}
