package org.nexus.common;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.nexus.exception.StateUpdateException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class StateFactoryTests {

    private static class Height implements State<Height> {
        private Set<String> hashes;
        private long evicted;

        public Height() {
        }

        public Height(Set<String> hashes, long evicted) {
            this.hashes = hashes;
            this.evicted = evicted;
        }

        @Override
        public void update(Block b, Transaction t) throws StateUpdateException {

        }

        @Override
        public void update(Header h) throws StateUpdateException {
            if (hashes == null) hashes = new HashSet<>();
            hashes.add(h.getHash().toString());
            if (hashes.size() > Byte.MAX_VALUE) {
                evicted += hashes.size();
                hashes = new HashSet<>();
            }
        }

        @Override
        public Height clone() {
            return new Height(hashes == null ? null : new HashSet<>(hashes), evicted);
        }

        public long getHeight() {
            return evicted + (hashes == null ? 0 : hashes.size());
        }
    }

    public static List<Block> getBlocks() throws Exception {
        return ChainCacheTest.getCache(0).getAll().stream().map(n -> new Block(
                Header.builder().hash(n.getHash())
                        .hashPrev(n.getHashPrev()).height(n.getHeight()).build()
        )).collect(Collectors.toList());
    }

    private StateFactory<Height> getStateFactory() throws Exception {
        Block genesis = getBlocks().get(0);
        StateFactory<Height> factory = new InMemoryStateFactory<>(genesis, new Height());
        List<Block> blocks = getBlocks();
        for (Block b : blocks.subList(1, blocks.size())) {
            factory.update(b);
        }
        return factory;
    }

    private StateRepository getRepository() throws Exception {
        Block genesis = getBlocks().get(0);
        StateRepository repository = new ConsortiumStateRepository();
        repository.register(genesis, new Height());
        List<Block> blocks = getBlocks();
        for (Block b : blocks.subList(1, blocks.size())) {
            repository.update(b);
        }
        return repository;
    }

    @Test
    public void testUpdate() throws Exception {
        getStateFactory();
    }

    @Test
    public void testGet() throws Exception {
        StateFactory<Height> factory = getStateFactory();
        assert factory.get(Hex.decodeHex("0206".toCharArray())).get().getHeight() == 6;
        for(Block b: getBlocks()){
            assert factory.get(b.getHash().getBytes()).isPresent();
        }
    }

    @Test
    public void testConfirm() throws Exception{
        StateFactory<Height> factory = getStateFactory();
        factory.confirm(Hex.decodeHex("0001".toCharArray()));
        assert !factory.get(Hex.decodeHex("0000".toCharArray())).isPresent();
        assert factory.get(Hex.decodeHex("0102".toCharArray())).isPresent();
        factory.confirm(Hex.decodeHex("0002".toCharArray()));
        for(Chained n :ChainCacheTest.getCache(0).getDescendants(Hex.decodeHex("0102".toCharArray()))){
            assert !factory.get(n.getHash().getBytes()).isPresent();
        }
    }

    @Test
    public void testGetLastConfirmed() throws Exception{
        StateFactory<Height> factory = getStateFactory();
        factory.confirm(Hex.decodeHex("0001".toCharArray()));
        factory.confirm(Hex.decodeHex("0002".toCharArray()));
        assert factory.getLastConfirmed().getHeight() == 2;
    }

    @Test
    public void testPut() throws Exception{
        StateFactory<Height> factory = getStateFactory();
        factory.put(new Chained() {
            @Override
            public HexBytes getHashPrev() {
                try {
                    return new HexBytes("0206");
                } catch (DecoderException e) {
                    return new HexBytes();
                }
            }

            @Override
            public HexBytes getHash() {
                try {
                    return new HexBytes("0207");
                } catch (DecoderException e) {
                    return new HexBytes();
                }
            }
        }, new Height(new HashSet<>(), 7));
        assert factory.get(Hex.decodeHex("0207".toCharArray())).get().getHeight() == 7;
    }

    @Test
    public void testRepository() throws Exception {
        StateRepository repository = getRepository();
        assert repository.get(Hex.decodeHex("0206".toCharArray()), Height.class).get().getHeight() == 6;
        for(Block b: getBlocks()){
            assert repository.get(b.getHash().getBytes(), Height.class).isPresent();
        }
        repository.confirm(Hex.decodeHex("0001".toCharArray()));
        assert !repository.get(Hex.decodeHex("0000".toCharArray()), Height.class).isPresent();
        assert repository.get(Hex.decodeHex("0102".toCharArray()), Height.class).isPresent();
        repository.confirm(Hex.decodeHex("0002".toCharArray()));
        for(Chained n :ChainCacheTest.getCache(0).getDescendants(Hex.decodeHex("0102".toCharArray()))){
            assert !repository.get(n.getHash().getBytes(), Height.class).isPresent();
        }
        assert repository.getLastConfirmed(Height.class).getHeight() == 2;
        repository.put(new Chained() {
            @Override
            public HexBytes getHashPrev() {
                try {
                    return new HexBytes("0206");
                } catch (DecoderException e) {
                    return new HexBytes();
                }
            }

            @Override
            public HexBytes getHash() {
                try {
                    return new HexBytes("0207");
                } catch (DecoderException e) {
                    return new HexBytes();
                }
            }
        }, new Height(new HashSet<>(), 7));
        assert repository.get(Hex.decodeHex("0207".toCharArray()), Height.class).get().getHeight() == 7;
    }
}
