package org.nexus.util;

import org.nexus.common.Header;
import org.nexus.common.Transaction;

import java.util.Objects;
import java.util.stream.Stream;

public class CommonUtil {
    public static byte[] getRaw(Transaction transaction) {
        return Stream.of(
                BigEndian.encodeInt32(transaction.getVersion()),
                BigEndian.encodeInt32(transaction.getType()),
                BigEndian.encodeInt64(transaction.getCreatedAt()),
                BigEndian.encodeInt64(transaction.getNonce()),
                transaction.getFrom() == null ? null : transaction.getFrom().getBytes(),
                BigEndian.encodeInt64(transaction.getGasPrice()),
                BigEndian.encodeInt64(transaction.getAmount()),
                transaction.getPayload() == null ? null : transaction.getPayload().getBytes(),
                transaction.getTo() == null ? null : transaction.getTo().getBytes(),
                transaction.getSignature() == null ? null : transaction.getSignature().getBytes()
        ).filter(Objects::nonNull).reduce(new byte[0], CommonUtil::concat);
    }

    public static byte[] getRaw(Header header) {
        return Stream.of(
                BigEndian.encodeInt32(header.getVersion()),
                header.getHashPrev() == null ? null : header.getHashPrev().getBytes(),
                header.getMerkleRoot() == null ? null : header.getMerkleRoot().getBytes(),
                BigEndian.encodeInt64(header.getHeight()),
                BigEndian.encodeInt64(header.getCreatedAt()),
                header.getPayload() == null ? null : header.getPayload().getBytes()
        ).filter(Objects::nonNull).reduce(new byte[0], CommonUtil::concat);
    }

    public static byte[] concat(byte[]... arrays) {
        int length = 0;
        for (byte[] array : arrays) {
            length += array.length;
        }
        byte[] result = new byte[length];
        int pos = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, pos, array.length);
            pos += array.length;
        }
        return result;
    }
}
