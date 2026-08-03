package org.nexus.consortium.net;

import com.google.common.base.Functions;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import org.nexus.consortium.proto.*;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Slf4j
// plugin for peers join/remove management
public class PeersManager implements Plugin {
    private ProtoPeerServer server;
    private PeerServerConfig config;
    private ConcurrentHashMap<PeerImpl, Boolean> pending = new ConcurrentHashMap<>();
    private static final int DISCOVERY_RATE = 15;

    public PeersManager(PeerServerConfig config) {
        this.config = config;
    }

    @Override
    public void onMessage(ContextImpl context, ProtoPeerServer server) {
        Client client = server.getClient();
        PeersCache cache = client.peersCache;
        MessageBuilder builder = client.messageBuilder;
        context.keep();
        switch (context.message.getCode()) {
            case PING:
                context.channel.write(builder.buildPong());
                return;
            case LOOK_UP:
                context.channel.write(
                    builder.buildPeers(server.getPeers())
                );
                return;
            case PEERS:
                if(!config.isEnableDiscovery()) return;
                try {
                    Peers.parseFrom(context.message.getBody()).getPeersList().stream()
                            .map(PeerImpl::parse)
                            .filter(Optional::isPresent)
                            .map(Optional::get)
                            .filter(x -> !cache.has(x) && !x.equals(server.getSelf()))
                            .forEach(x -> pending.put(x, true));
                } catch (InvalidProtocolBufferException e) {
                    log.error("parse peers message failed");
                }
        }
    }

    @Override
    public void onStart(ProtoPeerServer server) {
        this.server = server;
        Client client = server.getClient();
        PeersCache cache = client.peersCache;
        MessageBuilder builder = client.messageBuilder;

        // keep self alive
        Executors.newSingleThreadScheduledExecutor()
                .scheduleAtFixedRate(() -> client.broadcast(
                        builder.buildPing()
                ), 0, DISCOVERY_RATE, TimeUnit.SECONDS);
        Executors.newSingleThreadScheduledExecutor()
                .scheduleAtFixedRate(() -> {
                    lookup();
                    cache.half();
                    if(!config.isEnableDiscovery()) return;
                    pending.keySet()
                            .stream()
                            .filter(x -> !cache.has(x))
                            .limit(config.getMaxPeers())
                            .forEach(
                                    p -> client.dial(p, builder.buildPing())
                            );
                    pending.clear();
                }, 0, DISCOVERY_RATE, TimeUnit.SECONDS);
    }

    private void lookup(){
        Client client = server.getClient();
        PeersCache cache = client.peersCache;
        MessageBuilder builder = client.messageBuilder;

        if(!config.isEnableDiscovery()){
            // keep channel to bootstraps and trusted alive
            Stream.of(cache.bootstraps.keySet().stream(), cache.trusted.keySet().stream())
                    .flatMap(Functions.identity())
                    .filter(x -> !cache.has(x))
                    .forEach(x -> server.getClient().dial(x, builder.buildPing()));
            return;
        }
        if(cache.size() > 0){
            client.broadcast(builder.buildLookup());
            cache.trusted.keySet().forEach(p -> client.dial(p, builder.buildPing()));
            return;
        }
        Stream.of(cache.bootstraps, cache.trusted)
                .flatMap(x -> x.keySet().stream())
                .forEach(p -> client.dial(p, builder.buildLookup()));
    }

    @Override
    public void onNewPeer(PeerImpl peer, ProtoPeerServer server) {

    }

    @Override
    public void onDisconnect(PeerImpl peer, ProtoPeerServer server) {

    }
}
