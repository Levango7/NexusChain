package org.nexus.consortium;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;
import org.nexus.common.Block;
import org.nexus.common.Transaction;
import org.nexus.consortium.service.BlockRepositoryService;
import org.nexus.consortium.service.TransactionRepositoryService;
import org.nexus.util.BigEndian;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.nexus.consortium.TestUtils.BYTES;
import static org.nexus.consortium.TestUtils.getBlock;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = Start.class)
// use SPRING_CONFIG_LOCATION environment to locate spring config
// for example: SPRING_CONFIG_LOCATION=classpath:\application.yml,some-path\custom-config.yml
// set consensus.name = 'none' to run this test class
public class TransactionRepositoryTests {
    @Autowired
    private TransactionRepositoryService transactionStore;

    @Autowired
    private BlockRepositoryService blockStore;

    private void assertTransaction(Transaction transaction) {
        String h = Long.toString(BigEndian.decodeInt64(transaction.getBlockHash().getBytes()));
        assert Long.toString(transaction.getHeight()).equals(h);
        assert new String(transaction.getHash().getBytes()).startsWith(h);
        assert transaction.getVersion() == 0;
        assert Arrays.equals(transaction.getFrom().getBytes(), BYTES) &&
                Arrays.equals(transaction.getPayload().getBytes(), BYTES) &&
                Arrays.equals(transaction.getTo().getBytes(), BYTES) &&
                Arrays.equals(transaction.getSignature().getBytes(), BYTES);
    }

    @Before
    public void saveBlocks() {
        if (blockStore.getBlockByHeight(0).isPresent()) {
            return;
        }
        for (int i = 0; i < 10; i++) {
            Block b = getBlock(i);
            assert !blockStore.hasBlock(b.getHash().getBytes());
            blockStore.writeBlock(b);
        }
    }

    @Test
    public void test() {
        assert transactionStore != null;
        assert blockStore != null;
    }

    @Test
    public void testHasTransaction() {
        for (int i = 0; i < 10; i++) {
            for (int j = 0; j < 3; j++) {
                assert transactionStore.hasTransaction((i + "" + j).getBytes());
            }
        }
        assert !transactionStore.hasTransaction((1 + "" + 1000).getBytes());
    }

    @Test
    public void testHasPayload() {
        assert transactionStore.hasPayload(BYTES);
        assert !transactionStore.hasPayload(new byte[]{-1});
    }

    @Test
    public void testGetTransactionByHash() {
        Optional<Transaction> o = transactionStore.getTransactionByHash("00".getBytes());
        assert o.isPresent();
        assertTransaction(o.get());
    }

    @Test
    public void testGetTransactionByFrom() {
        assert transactionStore.getTransactionsByFrom(BYTES, 0, Integer.MAX_VALUE).size() == 30;
        List<Transaction> transactions = transactionStore.getTransactionsByFrom(BYTES, 0, 3);
        assert transactions.size() == 3;
        transactions.forEach(t -> {
            assert t.getHeight() == 0;
            assertTransaction(t);
        });
        assert transactionStore.getTransactionsByFrom("-1".getBytes(), 0, Integer.MAX_VALUE).size() == 0;
    }

    @Test
    public void testGetTransactionByFromAndType() {
        assert transactionStore.getTransactionsByFromAndType(BYTES, 0, 0, Integer.MAX_VALUE).size() == 30;
        List<Transaction> transactions = transactionStore.getTransactionsByFromAndType(BYTES, 0, 0, 3);
        assert transactions.size() == 3;
        transactions.forEach(t -> {
            assert t.getHeight() == 0;
            assertTransaction(t);
        });
        assert transactionStore.getTransactionsByFromAndType("-1".getBytes(), 0, 0, Integer.MAX_VALUE).size() == 0;
        assert transactionStore.getTransactionsByFromAndType(BYTES, -1, 0, Integer.MAX_VALUE).size() == 0;
    }

    @Test
    public void testGetTransactionByTo() {
        assert transactionStore.getTransactionsByTo(BYTES, 0, Integer.MAX_VALUE).size() == 30;
        List<Transaction> transactions = transactionStore.getTransactionsByTo(BYTES, 0, 3);
        assert transactions.size() == 3;
        transactions.forEach(t -> {
            assert t.getHeight() == 0;
            assertTransaction(t);
        });
        assert transactionStore.getTransactionsByTo("-1".getBytes(), 0, Integer.MAX_VALUE).size() == 0;
    }

    @Test
    public void testGetTransactionByToAndType() {
        assert transactionStore.getTransactionsByToAndType(BYTES, 0, 0, Integer.MAX_VALUE).size() == 30;
        List<Transaction> transactions = transactionStore.getTransactionsByToAndType(BYTES, 0, 0, 3);
        assert transactions.size() == 3;
        transactions.forEach(t -> {
            assert t.getHeight() == 0;
            assertTransaction(t);
        });
        assert transactionStore.getTransactionsByToAndType("-1".getBytes(), 0, 0, Integer.MAX_VALUE).size() == 0;
        assert transactionStore.getTransactionsByToAndType(BYTES, -1, 0, Integer.MAX_VALUE).size() == 0;
    }

    @Test
    public void testGetTransactionByFromAndTo() {
        assert transactionStore.getTransactionsByFromAndTo(BYTES, BYTES, 0, Integer.MAX_VALUE).size() == 30;
        List<Transaction> transactions = transactionStore.getTransactionsByFromAndTo(BYTES, BYTES, 0, 3);
        assert transactions.size() == 3;
        transactions.forEach(t -> {
            assert t.getHeight() == 0;
            assertTransaction(t);
        });
        assert transactionStore.getTransactionsByFromAndTo("-1".getBytes(), BYTES, 0, Integer.MAX_VALUE).size() == 0;
        assert transactionStore.getTransactionsByFromAndTo(BYTES, "-1".getBytes(), 0, Integer.MAX_VALUE).size() == 0;
    }

    @Test
    public void testGetTransactionByFromAndToAndType() {
        assert transactionStore.getTransactionsByFromAndToAndType(BYTES, BYTES, 0, 0, Integer.MAX_VALUE).size() == 30;
        List<Transaction> transactions = transactionStore.getTransactionsByFromAndToAndType(BYTES, BYTES, 0, 0, 3);
        assert transactions.size() == 3;
        transactions.forEach(t -> {
            assert t.getHeight() == 0;
            assertTransaction(t);
        });
        assert transactionStore.getTransactionsByFromAndToAndType("-1".getBytes(), BYTES, 0, 0, Integer.MAX_VALUE).size() == 0;
        assert transactionStore.getTransactionsByFromAndToAndType(BYTES, "-1".getBytes(), 0, 0, Integer.MAX_VALUE).size() == 0;
        assert transactionStore.getTransactionsByFromAndToAndType(BYTES, BYTES, -1, 0, Integer.MAX_VALUE).size() == 0;
    }

    @Test
    public void testGetTransactionsByPayload() {
        assert transactionStore.getTransactionsByPayload(BYTES, 0, Integer.MAX_VALUE).size() == 30;
        List<Transaction> transactions = transactionStore.getTransactionsByPayload(BYTES, 0, 3);
        assert transactions.size() == 3;
        transactions.forEach(t -> {
            assert t.getHeight() == 0;
            assertTransaction(t);
        });
        assert transactionStore.getTransactionsByPayload("-1".getBytes(), 0, Integer.MAX_VALUE).size() == 0;
    }

    @Test
    public void testGetTransactionsByPayloadAndType() {
        assert transactionStore.getTransactionsByPayloadAndType(BYTES, 0, 0, Integer.MAX_VALUE).size() == 30;
        List<Transaction> transactions = transactionStore.getTransactionsByPayloadAndType(BYTES, 0, 0, 3);
        assert transactions.size() == 3;
        transactions.forEach(t -> {
            assert t.getHeight() == 0;
            assertTransaction(t);
        });
        assert transactionStore.getTransactionsByPayloadAndType("-1".getBytes(), 0, 0, Integer.MAX_VALUE).size() == 0;
        assert transactionStore.getTransactionsByPayloadAndType(BYTES, -1, 0, Integer.MAX_VALUE).size() == 0;
    }

    @Test
    public void testGetTransactionsByBlockHash() {
        assert transactionStore.getTransactionsByBlockHash(BigEndian.encodeInt64(0), 0, Integer.MAX_VALUE).size() == 3;
        List<Transaction> transactions = transactionStore.getTransactionsByBlockHash(BigEndian.encodeInt64(0), 0, 1);
        assert transactions.size() == 1;
        transactions.forEach(t -> {
            assert t.getHeight() == 0;
            assertTransaction(t);
        });
        assert transactionStore.getTransactionsByBlockHash("-1".getBytes(), 0, Integer.MAX_VALUE).size() == 0;
    }

    @Test
    public void testGetTransactionsByBlockHeight() {
        assert transactionStore.getTransactionsByBlockHeight(0, 0, Integer.MAX_VALUE).size() == 3;
        List<Transaction> transactions = transactionStore.getTransactionsByBlockHeight(0, 0, 1);
        assert transactions.size() == 1;
        transactions.forEach(t -> {
            assert t.getHeight() == 0;
            assertTransaction(t);
        });
        assert transactionStore.getTransactionsByBlockHeight(-1, 0, Integer.MAX_VALUE).size() == 0;
    }
}
