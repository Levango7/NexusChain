package org.nexus.common;

// if T not implements Chained, wrap it as Chained
public class ChainedWrapper<T> implements Chained{
    protected HexBytes hashPrev;
    protected HexBytes hash;
    protected T data;

    public HexBytes getHashPrev() {
        return hashPrev;
    }

    public HexBytes getHash() {
        return hash;
    }

    public T get(){
        return data;
    }

    public ChainedWrapper() {
    }

    public ChainedWrapper(HexBytes hashPrev, HexBytes hash, T data) {
        this.hashPrev = hashPrev;
        this.hash = hash;
        this.data = data;
    }
}
