package org.nexus.common;

import java.util.Map;

public interface BatchAbleStore<K, V> extends Store<K, V>{
    void updateBatch(Map<K, V> rows);
}
