package org.nexus.common;

import org.nexus.exception.StateUpdateException;

import java.util.*;
import java.util.stream.Collectors;

/**
 * State tree for account related object storage
 */
public class InMemoryStateTree<T extends ForkAbleState<T>> implements StateTree<T>{
    private StateSet<T> root;
    private ChainCache<StateSet<T>> cache;
    private T some;

    public InMemoryStateTree(Block genesis, Collection<? extends T> states) {
        if (states.size() == 0) throw new RuntimeException("at lease one states required");
        some = states.stream().findFirst().get();
        root = new StateSet<>(genesis.getHashPrev(), genesis.getHash(), states);
        cache = new ChainCache<>();
    }

    public void update(Block b) {
        if (cache.contains(b.getHash().getBytes())) return;
        if (b.getHeight() == 0) {
            // manually assign genesis states is better
            return;
        }
        if (!cache.contains(
                b.getHashPrev().getBytes()) &&
                !b.getHashPrev().equals(root.getHash())
        )
            throw new RuntimeException(
                "state sets not found at " + b.getHashPrev()
        );
        Set<String> all = new HashSet<>();
        b.getBody().stream().map(some::getIdentifiersOf).forEach(all::addAll);
        Map<String, T> states = all.stream()
                .map(id -> this.get(id, b.getHashPrev().getBytes()).orElse(some.createEmpty(id)))
                .collect(Collectors.toMap(ForkAbleState::getIdentifier, (s) -> s));
        states.values().forEach(s -> {
            try {
                s.update(b.getHeader());
            } catch (StateUpdateException e) {
                e.printStackTrace();
            }
        });
        for (T s : states.values()) {
            b.getBody().forEach(tx -> {
                try {
                    s.update(b, tx);
                } catch (StateUpdateException e) {
                    e.printStackTrace();
                }
            });
        }
        put(b, states.values());
    }

    // provide all already updated state
    public void put(Chained node, Collection<? extends T> allStates) {
        if (cache.contains(node.getHash().getBytes())) return;
        StateSet<T> forked = new StateSet<>(node.getHashPrev(), node.getHash(), allStates);
        cache.put(forked);
    }

    public Optional<T> get(String id, byte[] where) {
        if (root.getHash().equals(new HexBytes(where))) {
            // WARNING: do not use method reference here State::clone
            return Optional.ofNullable(root.get().get(id)).map(s -> s.clone());
        }
        Optional<StateSet<T>> o = cache.get(where);
        if (!o.isPresent()) {
            return Optional.empty();
        }
        StateSet<T> set = o.get();
        if (set.get().containsKey(id))
            return Optional.of(set.get().get(id).clone());
        return get(id, set.getHashPrev().getBytes());
    }

    public T getLastConfirmed(String id) {
        if (root.get().containsKey(id)) return root.get().get(id).clone();
        return some.createEmpty(id);
    }

    public void confirm(byte[] hash) {
        HexBytes h = new HexBytes(hash);
        if (root.getHash().equals(h)) return;
        List<StateSet<T>> children = cache.getChildren(root.getHash().getBytes());
        Optional<StateSet<T>> o = children.stream().filter(x -> x.getHash().equals(h)).findFirst();
        if (!o.isPresent()) {
            throw new RuntimeException("the state to confirm not found or confirmed block is not child of current node");
        }
        StateSet<T> set = o.get();
        children.stream().filter(x -> !x.getHash().equals(h))
                .forEach(n -> cache.removeDescendants(n.getHash().getBytes()));
        cache.remove(set.getHash().getBytes());
        root.merge(set);
        root.hashPrev = root.hash;
        root.hash = h;
    }
}
