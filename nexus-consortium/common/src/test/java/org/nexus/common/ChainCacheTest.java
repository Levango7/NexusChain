package org.nexus.common;

import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.*;
import java.util.stream.Collectors;

// set env WRAPPER=true to test wrapper method
public class ChainCacheTest {

    public static class Node implements Chained {
        private HexBytes hash;
        private HexBytes hashPrev;
        private long height;

        public Node(HexBytes hash, HexBytes hashPrev, long height) {
            this.hash = hash;
            this.hashPrev = hashPrev;
            this.height = height;
        }

        @Override
        public HexBytes getHash() {
            return hash;
        }

        @Override
        public HexBytes getHashPrev() {
            return hashPrev;
        }

        public long getHeight() {
            return height;
        }
    }

    public static ChainCache<Node> getCache(int sizeLimit) throws Exception {

        Node genesis = new Node(new HexBytes("0000"), new HexBytes("ffff"), 0);
        List<String> hashes = Arrays.asList("0001", "0002", "0003", "0004", "0005");
        List<Node> chain0 = new ArrayList<>();
        for (int i = 0; i < hashes.size(); i++) {
            if (i == 0) {
                chain0.add(new Node(
                        new HexBytes(hashes.get(i)), new HexBytes("0000"), Long.parseLong(hashes.get(i)))
                );
                continue;
            }
            chain0.add(new Node(
                            new HexBytes(hashes.get(i)), new HexBytes(hashes.get(i - 1)),
                            Long.parseLong(hashes.get(i).substring(2))
                    )
            );
        }
        hashes = Arrays.asList("0102", "0103", "0104", "0105");
        List<Node> chain1 = new ArrayList<>();
        for (int i = 0; i < hashes.size(); i++) {
            if (i == 0) {
                chain1.add(new Node(
                                new HexBytes(hashes.get(i)), new HexBytes("0001"),
                                Long.parseLong(hashes.get(i).substring(2))
                        )
                );
                continue;
            }
            chain1.add(new Node(
                            new HexBytes(hashes.get(i)), new HexBytes(hashes.get(i - 1)),
                            Long.parseLong(hashes.get(i).substring(2))
                    )
            );
        }
        hashes = Arrays.asList("0204", "0205", "0206");
        List<Node> chain2 = new ArrayList<>();
        for (int i = 0; i < hashes.size(); i++) {
            if (i == 0) {
                chain2.add(new Node(
                        new HexBytes(hashes.get(i)), new HexBytes("0103"),
                        Long.parseLong(hashes.get(i).substring(2))
                ));
                continue;
            }
            chain2.add(new Node(
                    new HexBytes(hashes.get(i)), new HexBytes(hashes.get(i - 1)),
                    Long.parseLong(hashes.get(i).substring(2))

            ));
        }
        ChainCache<Node> cache;
        if ("true".equals(System.getenv("WRAPPER"))){
            cache = new ChainCacheWrapper<>(sizeLimit, Comparator.comparingLong(Node::getHeight));
        }else{
            cache = new ChainCache<>(sizeLimit, Comparator.comparingLong(Node::getHeight));
        }
        cache.put(genesis);
        cache.put(chain0);
        cache.put(chain1);
        cache.put(chain2);
        return cache;
    }

    @Test
    public void testGet() throws Exception {
        ChainCache<Node> cache = getCache(0);
        assert cache.get(Hex.decodeHex("0000".toCharArray())).isPresent();
        assert !cache.get(Hex.decodeHex("ffff".toCharArray())).isPresent();
    }

    @Test
    public void testGetDescendants() throws Exception {
        ChainCache<Node> cache = getCache(0);
        int size = cache.getDescendants(new HexBytes("0000").getBytes()).size();
        assert size == 13;
        assert cache.getDescendants(new HexBytes("0001").getBytes()).size() == 12;
        Set<String> nodes = cache.getDescendants(new HexBytes("0103").getBytes())
                .stream().map(n -> n.hash.toString()).collect(Collectors.toSet());
        assert nodes.size() == 6;
        assert nodes.containsAll(Arrays.asList("0103", "0104", "0105", "0204", "0205", "0206"));
    }

    @Test
    public void testEvict() throws Exception {
        ChainCache<Node> cache = getCache(12);
        int size = cache.size();
        assert cache.size() == 12;
        assert !cache.contains(Hex.decodeHex("0000".toCharArray()));
        cache = getCache(1);
        assert cache.size() == 1;
        assert cache.getAll().get(0).hash.toString().equals("0206");
    }

    @Test
    public void testRemove() throws Exception {
        ChainCache<Node> cache = getCache(0);
        cache.remove(cache.getAll().stream().map(n -> n.hash.getBytes()).collect(Collectors.toList()));
        assert cache.isEmpty();
        assert cache.size() == 0;
        cache = getCache(0);
        cache.remove(new HexBytes("0000").getBytes());
        assert cache.size() == 12;
        assert !cache.isEmpty();
    }

    @Test
    public void testGetAncestors() throws Exception {
        ChainCache<Node> cache = getCache(0);
        Set<String> ancestors = cache.getAncestors(
                new HexBytes("0206").getBytes()
        ).stream().map(n -> n.hash.toString()).collect(Collectors.toSet());

        assert ancestors.size() == 7;
        assert ancestors.containsAll(Arrays.asList("0204", "0205", "0206", "0102", "0103", "0001", "0000"));


        ancestors = cache.getAncestors(new HexBytes("0000").getBytes()).stream()
                .map(n -> n.hash.toString()).collect(Collectors.toSet());

        assert ancestors.size() == 1;
        assert ancestors.contains("0000");
    }

    @Test
    public void testGetAllForks() throws Exception {
        List<List<Node>> all = getCache(0).getAllForks();

        Map<String, Set<String>> forks = all.stream().collect(Collectors.toMap(
                l -> l.get(l.size() - 1).hash.toString(),
                x -> x.stream().map(n -> n.hash.toString()).collect(Collectors.toSet())
        ));
        assert forks.keySet().containsAll(Arrays.asList("0206", "0105", "0005"));
        assert forks.get("0005").size() == 6;
        assert forks.get("0005").containsAll(Arrays.asList("0000","0001", "0002", "0003", "0004", "0005"));
        assert forks.get("0105").size() == 6;
        assert forks.get("0105").containsAll(Arrays.asList("0000","0001", "0102", "0103", "0104", "0105"));
        assert forks.get("0206").size() == 7;
        assert forks.get("0206").containsAll(Arrays.asList("0000","0001", "0102", "0103", "0204", "0205", "0206"));
    }

    @Test
    public void popLongestChain() throws Exception {
        ChainCache<Node> cache = getCache(0);
        List<Node> longest = cache.popLongestChain();
        assert longest.get(longest.size() - 1).hash.toString().equals("0206");
        assert longest.size() == 7;
    }

    @Test
    public void testGetInitials() throws Exception{
        List<Node> initials = getCache(0).getInitials();
        assert initials.size() == 1;
        assert initials.get(0).hash.toString().equals("0000");
    }

    @Test
    public void testGetLeaves() throws Exception{
        Set<String> leaves = getCache(0).getLeaves().stream()
                .map(x -> x.hash.toString()).collect(Collectors.toSet());

        assert leaves.size() == 3;
        assert leaves.containsAll(Arrays.asList("0206", "0105", "0005"));
    }

    @Test
    public void testGetAllSorted() throws Exception{
        List<Node> nodes = getCache(0).getAll();
        List<Node> sorted = nodes.stream().sorted(Comparator.comparingLong(Node::getHeight)).collect(Collectors.toList());
        for(int i = 0; i < nodes.size(); i++){
            assert nodes.get(i).getHeight() == sorted.get(i).getHeight();
        }
    }

    @Test
    public void testGetChildren() throws Exception{
        assert getCache(0).getChildren(Hex.decodeHex("0206".toCharArray())).size() == 0;
        Set<String> children = getCache(0).getChildren(Hex.decodeHex("0103".toCharArray())).stream()
                .map(n -> n.hash.toString()).collect(Collectors.toSet());
        assert children.size() == 2;
        assert children.containsAll(Arrays.asList("0104", "0204"));
    }

    @Test
    public void testPutEvict() throws Exception{
        ChainCache<Node> cache = getCache(0);
        cache.put(new Node(HexBytes.parse("0206"), HexBytes.parse("0205"), 100), false);
        assert cache.get(Hex.decodeHex("0206".toCharArray())).get().getHeight() == 6;
        cache.put(new Node(HexBytes.parse("0206"), HexBytes.parse("0205"), 100), true);
        assert cache.get(Hex.decodeHex("0206".toCharArray())).get().getHeight() == 100;
    }
}
