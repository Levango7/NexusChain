package org.nexus.common;

import org.nexus.exception.StateUpdateException;

import java.util.List;
import java.util.Optional;

public class InMemoryStateFactory<T extends State<T>> implements StateFactory<T> {
    private ChainCache<ChainedWrapper<T>> cache;
    private HexBytes where;

    public InMemoryStateFactory(Block genesis, T state) {
        this.cache = new ChainCache<>();
        cache.put(new ChainedWrapper<>(genesis.getHashPrev(), genesis.getHash(), state));
        this.where = genesis.getHash();
    }

    public Optional<T> get(byte[] hash) {
        // warning: do not use method references here !!!
        // DO NOT USE cache.get(hash).map(ChainedState::get).map(Cloneable::clone)
        return cache.get(hash).map(s -> s.get()).map(s -> s.clone());
    }

    @Override
    public void update(Block b) {
        Optional<T> parent = get(b.getHashPrev().getBytes());
        if (!parent.isPresent()) throw new RuntimeException("state not found at " + b.getHashPrev());
        try {
            parent.get().update(b.getHeader());
        } catch (StateUpdateException e) {
            e.printStackTrace();
        }
        for (Transaction tx : b.getBody()) {
            try {
                parent.get().update(b, tx);
            } catch (StateUpdateException e) {
                // this should never happen, for the block b had been validated
                throw new RuntimeException(e.getMessage());
            }
        }
        cache.put(new ChainedWrapper<>(b.getHashPrev(), b.getHash(), parent.get()));
    }

    @Override
    public void put(Chained where, T state) {
        cache.put(new ChainedWrapper<>(where.getHashPrev(), where.getHash(), state));
    }

    @Override
    public void confirm(byte[] hash) {
        HexBytes h = new HexBytes(hash);
        List<ChainedWrapper<T>> children = cache.getChildren(where.getBytes());
        Optional<ChainedWrapper<T>> o = children.stream().filter(x -> x.getHash().equals(h)).findFirst();
        if (!o.isPresent()) {
            throw new RuntimeException("the state at "
                    + h +
                    " to confirm not found or confirmed block is not child of current node"
            );
        }
        children.stream().filter(x -> !x.getHash().equals(h))
                .forEach(n -> cache.removeDescendants(n.getHash().getBytes()));
        Optional<ChainedWrapper<T>> root = cache.get(where.getBytes());
        if (!root.isPresent()) {
            throw new RuntimeException("confirmed state missing");
        }
        cache.remove(root.get().getHash().getBytes());
        where = h;
    }

    @Override
    public T getLastConfirmed() {
        Optional<ChainedWrapper<T>> root = cache.get(where.getBytes());
        if (!root.isPresent()) {
            throw new RuntimeException("confirmed state missing");
        }
        return root.get().get().clone();
    }
}
