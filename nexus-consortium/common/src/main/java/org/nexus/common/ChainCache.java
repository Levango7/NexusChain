package org.nexus.common;

import lombok.NonNull;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;


/**
 * Tree-like object storage
 *
 * @author sal 1564319846@qq.com
 */
public class ChainCache<T extends Chained> implements Cloneable<ChainCache<T>> {
    private Map<String, T> nodes;
    private Map<String, Set<String>> childrenHashes;
    private Map<String, String> parentHash;
    private int sizeLimit;
    private Comparator<? super T> comparator;

    public ChainCache withComparator(Comparator<? super T> comparator) {
        this.comparator = comparator;
        return this;
    }

    // lru
    public ChainCache(int sizeLimit, Comparator<? super T> comparator) {
        this();
        this.sizeLimit = sizeLimit;
        this.comparator = comparator;
    }

    public ChainCache() {
        this.nodes = new HashMap<>();
        this.childrenHashes = new HashMap<>();
        this.parentHash = new HashMap<>();
    }

    public ChainCache(T node) {
        this();
        put(node);
    }

    public ChainCache(Collection<? extends T> nodes) {
        this();
        put(nodes);
    }

    public Optional<T> get(byte[] hash) {
        return Optional.ofNullable(nodes.get(HexBytes.encode(hash)));
    }

    private List<T> getNodes(Collection<String> hashes) {
        Stream<T> stream = new HashSet<>(hashes).stream()
                .map(k -> nodes.get(k))
                .filter(Objects::nonNull);
        if (comparator != null) stream = stream.sorted(comparator);
        return stream.collect(Collectors.toList());
    }

    public ChainCache<T> clone() {
        ChainCache<T> copied = new ChainCache<>();
        copied.nodes = new HashMap<>(nodes);
        copied.childrenHashes = new HashMap<>(childrenHashes);
        return copied;
    }

    private Set<String> getDescendantsHash(byte[] hash) {
        LinkedList<Set<String>> descendantBlocksHash = new LinkedList<>();
        String key = HexBytes.encode(hash);
        descendantBlocksHash.add(Collections.singleton(key));
        while (true) {
            Set<String> tmp = new HashSet<>();
            for (String k : descendantBlocksHash.getLast()) {
                if (!childrenHashes.containsKey(k)) {
                    continue;
                }
                tmp.addAll(childrenHashes.get(k));
            }
            if (tmp.size() == 0) {
                break;
            }
            descendantBlocksHash.add(tmp);
        }
        Set<String> all = descendantBlocksHash.stream().reduce(new HashSet<>(), (x, y) -> {
            Set<String> prev = new HashSet<>(x);
            prev.addAll(y);
            return prev;
        });
        return all;
    }

    public List<T> getDescendants(byte[] hash) {
        return getNodes(getDescendantsHash(hash));
    }

    public List<List<T>> getAllForks() {
        List<List<T>> res = getLeavesHash().stream()
                .map(k -> nodes.get(k))
                .map(k -> k.getHash().getBytes())
                .map(this::getAncestors)
                .sorted(Comparator.comparingLong(List::size))
                .collect(Collectors.toList());
        Collections.reverse(res);
        return res;
    }

    public void removeDescendants(byte[] hash) {
        getDescendantsHash(hash).forEach(this::remove);
    }

    private void remove(String key) {
        String prevHash = parentHash.get(key);
        nodes.remove(key);
        parentHash.remove(key);
        if (childrenHashes.containsKey(prevHash)) {
            childrenHashes.get(prevHash).remove(key);
        }
        if (childrenHashes.containsKey(prevHash) && childrenHashes.get(prevHash).size() == 0) {
            childrenHashes.remove(prevHash);
        }
    }

    public void remove(byte[] hash) {
        remove(HexBytes.encode(hash));
    }

    public void remove(Collection<byte[]> nodes) {
        for (byte[] node : nodes) {
            remove(node);
        }
    }

    // leaves not has children
    private Set<String> getLeavesHash() {
        Set<String> res = new HashSet<>();
        for (String key : nodes.keySet()) {
            if (!childrenHashes.containsKey(key) || childrenHashes.get(key).isEmpty()) {
                res.add(key);
            }
        }
        return res;
    }


    public List<T> getLeaves() {
        return getNodes(getLeavesHash());
    }

    public List<T> getInitials() {
        return getNodes(getInitialsHash());
    }

    // initials not has parent
    private Set<String> getInitialsHash() {
        Set<String> res = new HashSet<>();
        for (String key : nodes.keySet()) {
            T node = nodes.get(key);
            if (!nodes.containsKey(node.getHashPrev().toString())) {
                res.add(key);
            }
        }
        return res;
    }

    // evict
    private void evict() {
        if (comparator == null || sizeLimit <= 0) {
            return;
        }
        long toRemove = size() - sizeLimit;
        toRemove = toRemove > 0 ? toRemove : 0;
        this.nodes.values()
                .stream().sorted(comparator)
                .limit(toRemove)
                .map(n -> n.getHash().getBytes())
                .forEach(this::remove);
    }

    public void put(@NonNull T node) {
        put(node, false);
    }

    public void put(@NonNull Collection<? extends T> nodes) {
        put(nodes, false);
    }

    public void put(@NonNull T node, boolean evict) {
        String key = node.getHash().toString();
        if (evict) {
            remove(key);
        }
        if (nodes.containsKey(key)) return;
        nodes.put(key, node);
        String prevHash = node.getHashPrev().toString();
        childrenHashes.putIfAbsent(prevHash, new HashSet<>());
        childrenHashes.get(prevHash).add(key);
        parentHash.put(key, prevHash);
        evict();
    }

    public void put(@NonNull Collection<? extends T> nodes, boolean evict) {
        for (T b : nodes) {
            put(b, evict);
        }
    }

    public List<T> getAll() {
        return getNodes(nodes.keySet());
    }

    public List<T> popLongestChain() {
        List<List<T>> res = getAllForks();
        if (res.size() == 0) {
            return new ArrayList<>();
        }
        res.sort(Comparator.comparingInt(List::size));
        List<T> longest = res.get(res.size() - 1);
        remove(longest.stream().map(n -> n.getHash().getBytes()).collect(Collectors.toList()));
        return longest;
    }

    public int size() {
        return nodes.size();
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    public boolean contains(byte[] hash) {
        return nodes.containsKey(HexBytes.encode(hash));
    }


    public List<T> getAncestors(byte[] hash) {
        List<T> res = new ArrayList<>();
        T node = nodes.get(HexBytes.encode(hash));
        while (node != null) {
            res.add(node);
            node = nodes.get(node.getHashPrev().toString());
        }
        Collections.reverse(res);
        return res;
    }

    public List<T> getChildren(byte[] hash) {
        if (!childrenHashes.containsKey(HexBytes.encode(hash))) return new ArrayList<>();
        return getNodes(childrenHashes.get(HexBytes.encode(hash)));
    }
}
