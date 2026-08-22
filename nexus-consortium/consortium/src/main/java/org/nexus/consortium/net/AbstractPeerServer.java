package org.nexus.consortium.net;

import com.fasterxml.jackson.dataformat.javaprop.JavaPropsMapper;
import lombok.extern.slf4j.Slf4j;
import org.nexus.common.*;
import org.nexus.consortium.proto.*;
import org.nexus.exception.PeerServerLoadException;

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public abstract class AbstractPeerServer implements Channel.ChannelListener, ProtoPeerServer {
    protected PeerServerConfig config;
    private List<Plugin> plugins = new ArrayList<>();
    protected Client client;
    protected PeerImpl self;
    protected MessageBuilder builder;

    @Override
    public Peer getSelf() {
        return self;
    }

    @Override
    public void dial(Peer peer, byte[] message) {
        client.dial(peer, builder.buildAnother(message));
    }

    @Override
    public void broadcast(byte[] message) {
        client.broadcast(
                builder.buildMessage(Code.ANOTHER, config.getMaxTTL(), message)
        );
    }

    @Override
    public List<Peer> getBootStraps() {
        return new ArrayList<>(client.peersCache.bootstraps.keySet());
    }

    @Override
    public List<Peer> getPeers() {
        return client.peersCache.getPeers().collect(Collectors.toList());
    }

    @Override
    public void use(PeerServerListener... peerServerListeners) {
        for(PeerServerListener listener: peerServerListeners){
            plugins.add(new PluginWrapper(listener));
        }
    }

    abstract void startListening();

    abstract ChannelBuilder getChannelBuilder();

    @Override
    public void start() {
        plugins.forEach(l -> l.onStart(this));
        startListening();
        resolveHost();
        log.info("peer server is listening on {}", self.encodeURI());
        log.info("your p2p secret address is {}",
                String.format("%s://%s@%s:%d",
                        self.getProtocol(),
                        new HexBytes(
                                self.getPrivateKey().getEncoded(),
                                self.getPrivateKey().generatePublicKey().getEncoded())
                        ,
                        self.getHost(),
                        self.getPort()));
        if(config.getBootstraps() != null){
            client.bootstrap(config.getBootstraps());
        }
        if(config.getTrusted() != null){
            client.trust(config.getTrusted());
        }
    }

    @Override
    public void load(Properties properties) throws PeerServerLoadException {
        JavaPropsMapper mapper = new JavaPropsMapper();
        try {
            config = mapper.readPropertiesAs(properties, PeerServerConfig.class);
            if(config.getMaxTTL() <= 0) config.setMaxTTL(PeerServerConfig.DEFAULT_MAX_TTL);
            if(config.getMaxPeers() <= 0) config.setMaxPeers(PeerServerConfig.DEFAULT_MAX_PEERS);
        } catch (Exception e) {
            String schema = "";
            try {
                schema = mapper.writeValueAsProperties(
                        PeerServerConfig.builder()
                        .bootstraps(Collections.singletonList(new URI("node://localhost:9955")))
                        .build()
                ).toString();
            } catch (Exception ignored) {
            }
            throw new PeerServerLoadException(
                    "load properties failed :" + properties.toString() + " expecting " + schema
            );
        }
        if(!config.isEnableDiscovery() &&
                Stream.of(config.getBootstraps(), config.getTrusted())
                .filter(Objects::nonNull)
                .map(List::size).reduce(0, Integer::sum) == 0
            ){
            throw new PeerServerLoadException("cannot connect to any peer fot the discovery " +
                    "is disabled and none bootstraps and trusted provided");
        }
        try {
            self = PeerImpl.create(config.getAddress());
        } catch (Exception e) {
            throw new PeerServerLoadException("failed to load peer server invalid address " + config.getAddress());
        }
        builder = new MessageBuilder(self);
        client = new Client(self, config, builder, getChannelBuilder()).withListener(this);

        // loading plugins
        plugins.add(new MessageFilter(config));
        plugins.add(new PeersManager(config));
    }

    @Override
    public void onConnect(PeerImpl remote, Channel channel) {
        for (Plugin plugin : plugins) {
            plugin.onNewPeer(remote, this);
        }
    }

    @Override
    public void onMessage(Message message, Channel channel) {
        Optional<PeerImpl> peer = channel.getRemote();
        if (!peer.isPresent()) {
            channel.close();
            throw new RuntimeException("failed to parse peer");
        }
        ContextImpl context = ContextImpl.builder()
                .channel(channel)
                .message(message)
                .builder(builder)
                .remote(peer.get()).build();

        for (Plugin plugin : plugins) {
            plugin.onMessage(context, this);
            if(context.block){
                client.peersCache.block(peer.get());
                return;
            }
            if(context.disconnect){
                channel.close();
                client.peersCache.remove(peer.get());
                return;
            }
            if(context.exit){
                return;
            }
            if(context.keep){
                context.keep = false;
                client.peersCache.keep(peer.get(), channel);
            }
            if(context.relay && context.message.getTtl() > 0){
                context.relay = false;
                client.relay(context.message, peer.get());
            }
        }
    }

    @Override
    public void onError(Throwable throwable, Channel channel) {

    }

    @Override
    public void onClose(Channel channel) {
        if(channel.getRemote().isPresent()){
            for (Plugin plugin : plugins) {
                plugin.onDisconnect(channel.getRemote().get(), this);
            }
        }
    }

    @Override
    public Client getClient() {
        return client;
    }

    private void resolveHost(){
        if(!self.getHost().equals("localhost") && !self.getHost().equals("127.0.0.1")){
            return;
        }
        String externalIP = null;
        try{
            externalIP = Util.externalIp();
        }catch (Exception ignored){
            log.error("cannot get external ip, fall back to bind ip");
        }
        if (externalIP != null && Util.ping(externalIP, self.getPort())){
            log.info("ping {} success, set as your host", externalIP);
            self.setHost(externalIP);
            return;
        }
        String bindIP = null;
        try{
            bindIP = Util.bindIp();
        }catch (Exception e){
            log.error("get bind ip failed");
        }
        if (bindIP != null){
            self.setHost(bindIP);
        }
    }
}
