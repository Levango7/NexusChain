package org.nexus.db;

import org.springframework.stereotype.Component;
import org.nexus.consensus.pow.ValidatorState;
import org.nexus.core.state.StateFactory;

@Component
public class ValidatorStateFactory extends StateFactory<ValidatorState> {
    public ValidatorStateFactory(ValidatorState genesisState) {
        super(StateDB.CACHE_SIZE, genesisState);
    }
}
