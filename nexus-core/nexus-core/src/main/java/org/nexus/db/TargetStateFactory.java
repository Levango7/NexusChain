package org.nexus.db;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.nexus.consensus.pow.TargetState;
import org.nexus.core.state.EraLinkedStateFactory;

@Component
public class TargetStateFactory extends EraLinkedStateFactory<TargetState> {
    public TargetStateFactory(TargetState genesisState, @Value("${nexus.consensus.blocks-per-era}") int blocksPerEra) {
        super(StateDB.CACHE_SIZE, genesisState, blocksPerEra);
    }
}
