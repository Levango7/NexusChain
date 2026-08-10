package org.nexus.common;

import lombok.NonNull;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ChainCacheWrapper<T extends Chained> extends ChainCache<T> {
    private ReadWriteLock lock;

    public ChainCacheWrapper(int sizeLimit, Comparator<T> comparator) {
        super(sizeLimit, comparator);
        lock = new ReentrantReadWriteLock();
    }

    public ChainCacheWrapper() {
        super();
        lock = new ReentrantReadWriteLock();
    }

    public ChainCacheWrapper(T node) {
        super(node);
        lock = new ReentrantReadWriteLock();
    }

    public ChainCacheWrapper(Collection<? extends T> nodes) {
        super(nodes);
        lock = new ReentrantReadWriteLock();
    }

    @Override
    public Optional<T> get(byte[] hash) {
        lock.readLock().lock();
        try {
            return super.get(hash);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public ChainCache<T> clone() {
        lock.readLock().lock();
        try {
            return super.clone();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<T> getDescendants(byte[] hash) {
        lock.readLock().lock();
        try {
            return super.getDescendants(hash);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void removeDescendants(byte[] hash) {
        lock.writeLock().lock();
        try{
            super.removeDescendants(hash);
        }finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public List<List<T>> getAllForks() {
        lock.readLock().lock();
        try {
            return super.getAllForks();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void remove(byte[] hash) {
        lock.writeLock().lock();
        try{
            super.remove(hash);
        }finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public final void remove(Collection<byte[]> nodes) {
        lock.writeLock().lock();
        try{
            super.remove(nodes);
        }finally {
            lock.writeLock().unlock();
        }
    }


    @Override
    public List<T> getLeaves() {
        lock.readLock().lock();
        try {
            return super.getLeaves();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<T> getInitials() {
        lock.readLock().lock();
        try {
            return super.getInitials();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void put(T node, boolean evict) {
        lock.writeLock().lock();
        try {
            super.put(node, evict);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void put(Collection<? extends T> nodes, boolean evict) {
        lock.writeLock().lock();
        try {
            super.put(nodes, evict);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void put(@NonNull T node) {
        lock.writeLock().lock();
        try {
            super.put(node);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void put(@NonNull Collection<? extends T> nodes) {
        lock.writeLock().lock();
        try {
            super.put(nodes);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public List<T> getAll() {
        lock.readLock().lock();
        try {
            return super.getAll();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<T> popLongestChain() {
        lock.writeLock().lock();
        try{
            return super.popLongestChain();
        }finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public int size() {
        lock.readLock().lock();
        try{
            return super.size();
        }finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean isEmpty() {
        lock.readLock().lock();
        try{
            return super.isEmpty();
        }finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean contains(byte[] hash) {
        lock.readLock().lock();
        try{
            return super.contains(hash);
        }finally {
            lock.readLock().unlock();
        }
    }


    @Override
    public List<T> getAncestors(byte[] hash) {
        lock.readLock().lock();
        try{
            return super.getAncestors(hash);
        }finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<T> getChildren(byte[] hash) {
        lock.readLock().lock();
        try{
            return super.getChildren(hash);
        }finally {
            lock.readLock().unlock();
        }
    }


}
