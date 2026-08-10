package org.nexus.common;

import org.nexus.exception.StateUpdateException;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class ConsortiumStateRepository implements StateRepository {
    private Map<String, StateFactory> factories;

    private Map<String, InMemoryStateTree> trees;


    public ConsortiumStateRepository() {
        factories = new HashMap<>();
        trees = new HashMap<>();
    }

    @Override
    public <T extends State<T>> void register(Block genesis, T genesisState) throws StateUpdateException {
        factories.put(genesisState.getClass().toString(), new InMemoryStateFactory(genesis, genesisState));
    }

    @Override
    public <T extends ForkAbleState<T>> void register(Block genesis, Collection<? extends T> forkAbleStates) {
        if (forkAbleStates.size() == 0) throw new RuntimeException("requires at least one state");
        trees.put(
                forkAbleStates.stream().findFirst().get().getClass().toString(),
                new InMemoryStateTree<>(genesis, forkAbleStates)
        );
    }

    @Override
    public <T extends State<T>> Optional<T> get(byte[] hash, Class<T> clazz) {
        if (!factories.containsKey(clazz.toString())) return Optional.empty();
        return factories.get(clazz.toString()).get(hash);
    }

    @Override
    public <T extends ForkAbleState<T>> Optional<T> get(String id, byte[] hash, Class<T> clazz) {
        if (!trees.containsKey(clazz.toString())) return Optional.empty();
        Optional o = trees.get(clazz.toString()).get(id, hash);
        if (!o.isPresent()) return Optional.empty();
        return Optional.of((T) o.get());
    }

    @Override
    public void update(Block b) {
        factories.values().forEach(f -> f.update(b));
        trees.values().forEach(t -> t.update(b));
    }

    @Override
    public void put(Chained chained, State state) {
        if (!factories.containsKey(state.getClass().toString())) throw new RuntimeException(
                state.getClass().toString() + " has not been registered"
        );
        factories.get(state.getClass().toString()).put(chained, state);
    }

    @Override
    public void put(Chained chained, Collection<ForkAbleState> forkAbleStates, Class<? extends ForkAbleState> clazz) {
        String k = clazz.toString();
        if (!trees.containsKey(k)) throw new RuntimeException(
                clazz.toString() + " has not been registered"
        );
        trees.get(k).put(chained, forkAbleStates);
    }

    @Override
    public void confirm(byte[] hash) {
        factories.values().forEach(f -> f.confirm(hash));
        trees.values().forEach(t -> t.confirm(hash));
    }

    @Override
    public <T extends State<T>> T getLastConfirmed(Class<T> clazz) {
        return (T) factories.get(clazz.toString()).getLastConfirmed();
    }

    @Override
    public <T extends ForkAbleState<T>> T getLastConfirmed(String id, Class<T> clazz) {
        return (T) trees.get(clazz.toString()).getLastConfirmed(id);
    }

    @Override
    public void onBlockWritten(Block block) {
        update(block);
    }

    @Override
    public void onNewBestBlock(Block block) {

    }

    @Override
    public void onBlockConfirmed(Block block) {
        confirm(block.getHash().getBytes());
    }
}
