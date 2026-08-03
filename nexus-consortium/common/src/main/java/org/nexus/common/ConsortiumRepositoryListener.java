package org.nexus.common;

public interface ConsortiumRepositoryListener{
    void onBlockWritten(Block block);
    void onNewBestBlock(Block block);
    void onBlockConfirmed(Block block);
}
