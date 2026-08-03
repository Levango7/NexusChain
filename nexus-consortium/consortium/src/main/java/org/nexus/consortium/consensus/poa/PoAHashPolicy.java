package org.nexus.consortium.consensus.poa;

import org.nexus.common.*;

public class PoAHashPolicy implements HashPolicy {
    static final HashPolicy HASH_POLICY = new PoAHashPolicy();

    @Override
    public HexBytes getHash(Block block) {
        return new HexBytes(PoAUtils.getHash(block));
    }

    @Override
    public HexBytes getHash(Transaction transaction) {
        return new HexBytes(PoAUtils.getHash(transaction));
    }

    @Override
    public HexBytes getHash(Header header) {
        return new HexBytes(PoAUtils.getHash(header));
    }
}
