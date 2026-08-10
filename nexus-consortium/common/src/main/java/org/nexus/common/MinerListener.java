package org.nexus.common;

public interface MinerListener {

    void onBlockMined(Block block);

    void onMiningFailed(Block block);
}
