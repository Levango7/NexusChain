package org.nexus.common;

import java.util.*;

public interface StateTree<T extends ForkAbleState<T>> {
    void update(Block b);

    // provide all already updated state
    void put(Chained node, Collection<? extends T> allStates);

    Optional<T> get(String id, byte[] where);

    T getLastConfirmed(String id);

    void confirm(byte[] hash);
}
