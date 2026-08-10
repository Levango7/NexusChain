package org.nexus.common;

public interface HashPolicy {
    HexBytes getHash(Block block);
    HexBytes getHash(Transaction transaction);
    HexBytes getHash(Header header);
}
