package org.nexus.common;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.nexus.exception.StateUpdateException;

import java.util.*;
import java.util.stream.Collectors;

@RunWith(JUnit4.class)
public class StateTreeTests {
    private static final HexBytes ADDRESS_A = new HexBytes(new byte[]{0x0a});
    private static final HexBytes ADDRESS_B = new HexBytes(new byte[]{0x0b});
    private static final HexBytes ADDRESS_C = new HexBytes(new byte[]{0x0c});

    // account to test
    public static class Account implements ForkAbleState<Account> {
        private String address;
        private long balance;

        @Override
        public String getIdentifier() {
            return address;
        }

        @Override
        public Set<String> getIdentifiersOf(Transaction transaction) {
            return new HashSet<>(Arrays.asList(transaction.getFrom().toString(), transaction.getTo().toString()));
        }

        @Override
        public Account createEmpty(String id) {
            return new Account(id, 0);
        }

        @Override
        public void update(Block b, Transaction t) throws StateUpdateException {
            if (t.getFrom().toString().equals(address)) {
                balance -= t.getAmount();
            }
            if (t.getTo().toString().equals(address)) {
                balance += t.getAmount();
            }
        }

        @Override
        public void update(Header header) throws StateUpdateException {

        }

        @Override
        public Account clone() {
            return new Account(address, balance);
        }

        public String getAddress() {
            return address;
        }

        public long getBalance() {
            return balance;
        }

        public Account(String address, long balance) {
            this.address = address;
            this.balance = balance;
        }
    }

    private InMemoryStateTree<Account> getTree() throws Exception {
        Map<String, Block> blocks = StateFactoryTests.getBlocks().stream()
                .collect(Collectors.toMap(
                        b -> b.getHash().toString(),
                        b -> b
                ));
        blocks.get("0002").getBody().add(Transaction.builder().from(ADDRESS_A).to(ADDRESS_B).amount(50).build());
        blocks.get("0102").getBody().add(Transaction.builder().from(ADDRESS_A).to(ADDRESS_B).amount(60).build());
        InMemoryStateTree<Account> tree = new InMemoryStateTree<>(
                blocks.get("0000"),
                Arrays.asList(new Account(ADDRESS_A.toString(), 100),
                        new Account(ADDRESS_B.toString(), 100))
        );
        blocks.values().stream().sorted(Comparator.comparingLong(Block::getHeight)).forEach(tree::update);
        return tree;
    }

    private StateRepository getRepository() throws Exception {
        StateRepository repository = new ConsortiumStateRepository();
        Map<String, Block> blocks = StateFactoryTests.getBlocks().stream()
                .collect(Collectors.toMap(
                        b -> b.getHash().toString(),
                        b -> b
                ));
        blocks.get("0002").getBody().add(Transaction.builder().from(ADDRESS_A).to(ADDRESS_B).amount(50).build());
        blocks.get("0102").getBody().add(Transaction.builder().from(ADDRESS_A).to(ADDRESS_B).amount(60).build());
        repository.register(blocks.get("0000"), Arrays.asList(new Account(ADDRESS_A.toString(), 100),
                new Account(ADDRESS_B.toString(), 100)));
        blocks.values().stream().sorted(Comparator.comparingLong(Block::getHeight)).forEach(repository::update);
        return repository;
    }

    @Test
    public void testGetTree() throws Exception {
        getTree();
    }

    @Test
    public void testGetState() throws Exception {
        Optional<Account> o = getTree().get(ADDRESS_A.toString(), Hex.decodeHex("0000".toCharArray()));
        assert o.isPresent();
        assert o.get().balance == 100;
    }

    @Test
    public void testUpdate() throws Exception {
        Optional<Account> o = getTree().get(ADDRESS_A.toString(), Hex.decodeHex("0002".toCharArray()));
        assert o.isPresent();
        assert o.get().balance == 50;
        o = getTree().get(ADDRESS_B.toString(), Hex.decodeHex("0002".toCharArray()));
        assert o.isPresent();
        assert o.get().balance == 150;
        o = getTree().get(ADDRESS_A.toString(), Hex.decodeHex("0102".toCharArray()));
        assert o.isPresent();
        assert o.get().balance == 40;
        o = getTree().get(ADDRESS_B.toString(), Hex.decodeHex("0102".toCharArray()));
        assert o.isPresent();
        assert o.get().balance == 160;
        o = getTree().get(ADDRESS_B.toString(), Hex.decodeHex("0105".toCharArray()));
        assert o.isPresent();
        assert o.get().balance == 160;
    }

    @Test
    public void testConfirm() throws Exception {
        InMemoryStateTree<Account> tree = getTree();
        tree.confirm(Hex.decodeHex("0001".toCharArray()));
        tree.confirm(Hex.decodeHex("0102".toCharArray()));
        Optional<Account> o = tree.get(ADDRESS_B.toString(), Hex.decodeHex("0002".toCharArray()));
        assert !o.isPresent();
        assert tree.getLastConfirmed(ADDRESS_B.toString()).getBalance() == 160;
    }

    @Test
    public void testImmutable() throws Exception {
        InMemoryStateTree<Account> tree = getTree();
        Account a = tree.getLastConfirmed(ADDRESS_A.toString());
        a.balance = 0;
        assert tree.getLastConfirmed(ADDRESS_A.toString()).balance == 100;
    }

    @Test
    public void testPut() throws Exception{
        InMemoryStateTree<Account> tree = getTree();
        tree.put(new Chained() {
            @Override
            public HexBytes getHashPrev() {
                try {
                    return new HexBytes("0206");
                } catch (DecoderException e) {
                    return new HexBytes();
                }
            }

            @Override
            public HexBytes getHash() {
                try {
                    return new HexBytes("0207");
                } catch (DecoderException e) {
                    return new HexBytes();
                }
            }
        }, Collections.singletonList(new Account(ADDRESS_C.toString(), 1000)));
        assert tree.get(ADDRESS_C.toString(), Hex.decodeHex("0207".toCharArray())).get().balance == 1000;
    }

    @Test
    public void testRepository() throws Exception {
        StateRepository repository = getRepository();
        Optional<Account> o = repository.get(ADDRESS_A.toString(), Hex.decodeHex("0002".toCharArray()), Account.class);
        assert o.isPresent();
        assert o.get().balance == 50;
        o = repository.get(ADDRESS_B.toString(), Hex.decodeHex("0002".toCharArray()), Account.class);
        assert o.isPresent();
        assert o.get().balance == 150;
        o = repository.get(ADDRESS_A.toString(), Hex.decodeHex("0102".toCharArray()), Account.class);
        assert o.isPresent();
        assert o.get().balance == 40;
        o = repository.get(ADDRESS_B.toString(), Hex.decodeHex("0102".toCharArray()), Account.class);
        assert o.isPresent();
        assert o.get().balance == 160;
        o = repository.get(ADDRESS_B.toString(), Hex.decodeHex("0105".toCharArray()), Account.class);
        assert o.isPresent();
        assert o.get().balance == 160;
        repository.confirm(Hex.decodeHex("0001".toCharArray()));
        repository.confirm(Hex.decodeHex("0102".toCharArray()));
        o = repository.get(ADDRESS_B.toString(), Hex.decodeHex("0002".toCharArray()), Account.class);
        assert !o.isPresent();
        assert repository.getLastConfirmed(ADDRESS_B.toString(), Account.class).getBalance() == 160;
        repository.put(new Chained() {
            @Override
            public HexBytes getHashPrev() {
                try {
                    return new HexBytes("0206");
                } catch (DecoderException e) {
                    return new HexBytes();
                }
            }

            @Override
            public HexBytes getHash() {
                try {
                    return new HexBytes("0207");
                } catch (DecoderException e) {
                    return new HexBytes();
                }
            }
        }, Collections.singletonList(new Account(ADDRESS_C.toString(), 1000)), Account.class);
        assert repository.get(ADDRESS_C.toString(), Hex.decodeHex("0207".toCharArray()), Account.class).get().balance == 1000;
    }
}
