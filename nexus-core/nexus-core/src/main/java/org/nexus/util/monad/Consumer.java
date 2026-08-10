package org.nexus.util.monad;

public interface Consumer<T, E extends Exception> {
    void consume(T data) throws E;
}
