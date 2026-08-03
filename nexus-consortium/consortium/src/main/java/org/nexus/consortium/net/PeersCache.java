package org.nexus.consortium.net;

import org.nexus.common.HexBytes;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// peers cache for peer searching/discovery
public class PeersCache {
    private static final int PEER_SCORE = 32;
    private static final int EVIL_SCORE = -(1 << 31);
    private PeerServerConfig config;

    static class Bucket {
        Map<PeerImpl, Channel> channels = new ConcurrentHashMap<>();
    }

    private Bucket[] peers = new Bucket[256];

    Map<PeerImpl, Boolean> bootstraps = new ConcurrentHashMap<>();

    Map<PeerImpl, Boolean> blocked = new ConcurrentHashMap<>();

    Map<PeerImpl, Boolean> trusted = new ConcurrentHashMap<>();

    private PeerImpl self;

    public PeersCache(
            PeerImpl self,
            PeerServerConfig config
    ) {
        this.self = self;
        this.config = config;
    }

    public int size() {
        return Stream.of(peers)
                .filter(Objects::nonNull)
                .map(x -> x.channels.size())
                .reduce(0, Integer::sum);
    }

    public boolean has(PeerImpl peer) {
        int idx = self.subTree(peer);
        return peers[idx] != null && peers[idx].channels.containsKey(peer);
    }

    void keep(PeerImpl peer, Channel channel) {
        if (peer.equals(self)) {
            return;
        }
        if(blocked.containsKey(peer)) return;
        int idx = self.subTree(peer);
        if (peers[idx] == null) {
            peers[idx] = new Bucket();
        }

        // if the peer already had been put
        Optional<PeerImpl> o = peers[idx].channels.keySet().stream()
                .filter(k -> k.equals(peer)).findAny();

        // increase its score
        if (o.isPresent()) {
            PeerImpl p = o.get();
            p.setScore(p.getScore() + PEER_SCORE);
            peers[idx].channels.put(p, channel);
            return;
        }

        peer.setScore(PEER_SCORE);

        if (size() < config.getMaxPeers()) {
            peers[idx].channels.put(peer, channel);
            return;
        }

        // when neighbours is full, check whether some neighbours could be removed
        // 1. the bucket of the new neighbour is empty
        if (peers[idx].channels.size() > 0) {
            channel.close();
            return;
        }

        // 2. exists some bucket which contains more than one peer
        Optional<Map<PeerImpl, Channel>> bucket = Stream.of(peers)
                .filter(Objects::nonNull)
                .map(x -> x.channels)
                .max(Comparator.comparingInt(Map::size));

        if (!bucket.isPresent() || bucket.get().size() <= 1) {
            channel.close();
            return;
        }

        // the conditions above are both filled
        bucket.get().keySet()
                .stream().findAny()
                .ifPresent(this::remove);

        peers[idx].channels.put(peer, channel);
    }

    // remove the peer and close the channel
    void remove(PeerImpl peer) {
        int idx = self.subTree(peer);
        if (peers[idx] == null) {
            return;
        }
        Channel ch = peers[idx].channels.get(peer);
        peers[idx].channels.remove(peer);
        if (ch == null) return;
        ch.close();
    }

    // get limit peers randomly
    public List<PeerImpl> getPeers(int limit) {
        List<PeerImpl> res = getPeers().collect(Collectors.toList());
        Random rand = new Random();
        while (res.size() > 0 && res.size() > limit) {
            int idx = Math.abs(rand.nextInt()) % res.size();
            res.remove(idx);
        }
        return res;
    }

    Stream<PeerImpl> getPeers() {
        return Stream.of(peers)
                .filter(Objects::nonNull)
                .flatMap(x -> x.channels.keySet().stream());
    }

    public void block(PeerImpl peer) {
        if(trusted.containsKey(peer)) return;
        if(!config.isEnableDiscovery() && bootstraps.containsKey(peer)) return;
        if(blocked.containsKey(peer)){
            blocked.keySet().stream()
                    .filter(p -> p.equals(peer))
                    .forEach(x -> x.setScore(EVIL_SCORE));
            return;
        }
        remove(peer);
        peer.score = EVIL_SCORE;
        blocked.put(peer, true);
    }

    // decrease score of peer
    public void half(PeerImpl peer) {
        int idx = self.subTree(peer);
        if (peers[idx] == null) return;
        peers[idx].channels.keySet()
                .stream()
                .filter(p -> p.equals(peer))
                .findAny()
                .filter(p -> {
                    p.score /= 2;
                    return p.score == 0;
                })
                .ifPresent(this::remove);
    }

    // decrease score of all peer
    public void half() {
        List<PeerImpl> toRemoves = Stream.of(peers).filter(Objects::nonNull)
                .flatMap(x -> x.channels.keySet().stream())
                .filter(p -> {
                    p.score /= 2;
                    return p.score == 0;
                }).collect(Collectors.toList());
        toRemoves.forEach(this::remove);
        List<PeerImpl> toRestores
                = blocked.keySet().stream()
                .filter(p -> {
                    p.score /= 2;
                    return p.score == 0;
                }).collect(Collectors.toList());
        toRestores.forEach(p -> blocked.remove(p));
    }

    public boolean isFull() {
        return size() >= config.getMaxPeers();
    }

    Stream<Channel> getChannels(){
        return Arrays.stream(peers).filter(Objects::nonNull)
                .flatMap(x -> x.channels.values().stream());
    }

    Optional<Channel> getChannel(PeerImpl peer){
        int idx = self.subTree(peer);
        if (peers[idx] == null) return Optional.empty();
        return Optional.ofNullable(peers[idx].channels.get(peer));
    }

    Optional<Channel> getChannel(HexBytes id){
        int idx = self.subTree(id.getBytes());
        if (peers[idx] == null) return Optional.empty();
        return peers[idx].channels.keySet().stream()
                .filter(p -> p.getID().equals(id))
                .findAny()
                .map(p -> peers[idx].channels.get(p))
                ;
    }

    boolean hasBlocked(PeerImpl peer){
        return blocked.containsKey(peer);
    }
}
