package org.nexus.p2p;

import com.google.protobuf.AbstractMessage;
import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.nexus.encoding.BigEndian;
import org.nexus.util.Arrays;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class Util {
    private static final long MAX_MESSAGE_SIZE = 4 * (1 << 20) - 128 * 1024;

    private static final Logger logger = LoggerFactory.getLogger(Util.class);

    public static byte[] getRawForSign(NexusChainOuterClass.Message msg) {
        return Arrays.concatenate(new byte[][]{
                        BigEndian.encodeUint32(msg.getCode().getNumber()),
                        BigEndian.encodeUint64(msg.getCreatedAt().getSeconds()),
                        msg.getRemotePeer().getBytes(StandardCharsets.UTF_8),
                        BigEndian.encodeUint64(msg.getTtl()),
                        BigEndian.encodeUint64(msg.getNonce()),
                        msg.getBody().toByteArray()
                }
        );
    }

    private static NexusChainOuterClass.Message.Builder buildMessageBuilder(Peer self, long nonce, long ttl) {
        NexusChainOuterClass.Message.Builder builder = NexusChainOuterClass.Message.newBuilder();
        builder.setCreatedAt(Timestamp.newBuilder().setSeconds(System.currentTimeMillis() / 1000).build());
        builder.setRemotePeer(self.toString());
        builder.setTtl(ttl);
        builder.setNonce(nonce);
        return builder;
    }

    private static <T> void addIfNotEmpty(List<List<T>> lists, List<T> list){
        if (list != null && list.size() > 0){
            lists.add(list);
        }
    }

    private static <T extends AbstractMessage> List<List<T>> split(Iterable<T> msgs) {
        List<T> tmp = new ArrayList<>();
        List<List<T>> divided = new ArrayList<>();

        for (T o : msgs) {
            if (tmp
                    .stream()
                    .map(AbstractMessage::getSerializedSize)
                    .reduce(Integer::sum).orElse(0) + o.getSerializedSize() > MAX_MESSAGE_SIZE
            ) {
                addIfNotEmpty(divided, tmp);
                tmp = new ArrayList<>();
                tmp.add(o);
            } else {
                tmp.add(o);
            }
        }
        addIfNotEmpty(divided, tmp);
        return divided;
    }

    public static List<NexusChainOuterClass.Blocks> split(NexusChainOuterClass.Blocks msg) {
        List<List<NexusChainOuterClass.Block>> blockLists = split(msg.getBlocksList());
        return blockLists.stream().map(blocks -> NexusChainOuterClass.Blocks.newBuilder().addAllBlocks(blocks).build())
                .collect(Collectors.toList());
    }

    public static List<NexusChainOuterClass.Transactions> split(NexusChainOuterClass.Transactions msg) {
        List<List<NexusChainOuterClass.Transaction>> transactionLists = split(msg.getTransactionsList());
        return transactionLists.stream().map(transactions -> NexusChainOuterClass.Transactions.newBuilder().addAllTransactions(transactions).build())
                .collect(Collectors.toList());
    }

    // List<NexusChainOutClass.Message> 16M
    public static NexusChainOuterClass.Message buildMessage(Peer self, long nonce, long ttl, AbstractMessage msg) {
        if (msg instanceof NexusChainOuterClass.Nothing) {
            NexusChainOuterClass.Message.Builder builder = buildMessageBuilder(self, nonce, ttl);
            builder.setCode(NexusChainOuterClass.Code.NOTHING);
            return sign(self, builder.setBody(msg.toByteString())).build();
        }
        if (msg instanceof NexusChainOuterClass.Ping) {
            NexusChainOuterClass.Message.Builder builder = buildMessageBuilder(self, nonce, ttl);
            builder.setCode(NexusChainOuterClass.Code.PING);
            return sign(self, builder.setBody(msg.toByteString())).build();
        }
        if (msg instanceof NexusChainOuterClass.Pong) {
            NexusChainOuterClass.Message.Builder builder = buildMessageBuilder(self, nonce, ttl);
            builder.setCode(NexusChainOuterClass.Code.PONG);
            return sign(self, builder.setBody(msg.toByteString())).build();
        }
        if (msg instanceof NexusChainOuterClass.Lookup) {
            NexusChainOuterClass.Message.Builder builder = buildMessageBuilder(self, nonce, ttl);
            builder.setCode(NexusChainOuterClass.Code.LOOK_UP);
            return sign(self, builder.setBody(msg.toByteString())).build();
        }
        if (msg instanceof NexusChainOuterClass.Peers) {
            NexusChainOuterClass.Message.Builder builder = buildMessageBuilder(self, nonce, ttl);
            builder.setCode(NexusChainOuterClass.Code.PEERS);
            return sign(self, builder.setBody(msg.toByteString())).build();
        }
        if (msg instanceof NexusChainOuterClass.GetStatus) {
            NexusChainOuterClass.Message.Builder builder = buildMessageBuilder(self, nonce, ttl);
            builder.setCode(NexusChainOuterClass.Code.GET_STATUS);
            return sign(self, builder.setBody(msg.toByteString())).build();
        }
        if (msg instanceof NexusChainOuterClass.Status) {
            NexusChainOuterClass.Message.Builder builder = buildMessageBuilder(self, nonce, ttl);
            builder.setCode(NexusChainOuterClass.Code.STATUS);
            return sign(self, builder.setBody(msg.toByteString())).build();
        }
        if (msg instanceof NexusChainOuterClass.GetBlocks) {
            NexusChainOuterClass.Message.Builder builder = buildMessageBuilder(self, nonce, ttl);
            builder.setCode(NexusChainOuterClass.Code.GET_BLOCKS);
            return sign(self, builder.setBody(msg.toByteString())).build();
        }
        if (msg instanceof NexusChainOuterClass.Blocks) {
            NexusChainOuterClass.Message.Builder builder = buildMessageBuilder(self, nonce, ttl);
            builder.setCode(NexusChainOuterClass.Code.BLOCKS);
            return sign(self, builder.setBody(msg.toByteString())).build();
        }
        if (msg instanceof NexusChainOuterClass.Proposal) {
            NexusChainOuterClass.Message.Builder builder = buildMessageBuilder(self, nonce, ttl);
            builder.setCode(NexusChainOuterClass.Code.PROPOSAL);
            return sign(self, builder.setBody(msg.toByteString())).build();
        }
        if (msg instanceof NexusChainOuterClass.Transactions) {
            NexusChainOuterClass.Message.Builder builder = buildMessageBuilder(self, nonce, ttl);
            builder.setCode(NexusChainOuterClass.Code.TRANSACTIONS);
            return sign(self, builder.setBody(msg.toByteString())).build();
        }
        if (msg instanceof NexusChainOuterClass.GetTreeNodes) {
            NexusChainOuterClass.Message.Builder builder = buildMessageBuilder(self, nonce, ttl);
            builder.setCode(NexusChainOuterClass.Code.GET_TREE_NODES);
            return sign(self, builder.setBody(msg.toByteString())).build();
        }
        if (msg instanceof NexusChainOuterClass.TreeNodes) {
            NexusChainOuterClass.Message.Builder builder = buildMessageBuilder(self, nonce, ttl);
            builder.setCode(NexusChainOuterClass.Code.TREE_NODES);
            return sign(self, builder.setBody(msg.toByteString())).build();
        }
        if (msg instanceof NexusChainOuterClass.GetMerkleTransactions) {
            NexusChainOuterClass.Message.Builder builder = buildMessageBuilder(self, nonce, ttl);
            builder.setCode(NexusChainOuterClass.Code.GET_MERKELE_TRANSACTIONS);
            return sign(self, builder.setBody(msg.toByteString())).build();
        }
        if (msg instanceof NexusChainOuterClass.MerkleTransactions) {
            NexusChainOuterClass.Message.Builder builder = buildMessageBuilder(self, nonce, ttl);
            builder.setCode(NexusChainOuterClass.Code.MERKLE_TRANSACTIONS);
            return sign(self, builder.setBody(msg.toByteString())).build();
        }
        logger.error("cannot deduce message type " + msg.getClass().toString());
        NexusChainOuterClass.Message.Builder builder = buildMessageBuilder(self, nonce, ttl);
        builder.setCode(NexusChainOuterClass.Code.NOTHING).setBody(NexusChainOuterClass.Nothing.newBuilder().build().toByteString());
        return sign(self, builder).build();
    }

    public static NexusChainOuterClass.Message.Builder sign(Peer self, NexusChainOuterClass.Message.Builder builder) {
        return builder.setSignature(
                ByteString.copyFrom(
                        self.privateKey.sign(Util.getRawForSign(builder.build()))
                )
        );
    }
}
