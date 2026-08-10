package org.nexus.common;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class StateSet<T extends ForkAbleState<T>> extends ChainedWrapper<Map<String, T>> {
    public StateSet(HexBytes hashPrev, HexBytes hash, Collection<? extends T> states) {
        this.data = states.stream().collect(Collectors.toMap(ForkAbleState::getIdentifier, Function.identity()));
        this.hashPrev = hashPrev;
        this.hash = hash;
    }

    void merge(StateSet<T> set) {
        for (String k : set.data.keySet()) {
            data.put(k, set.data.get(k));
        }
    }
}
