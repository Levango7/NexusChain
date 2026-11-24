package org.nexus.core;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.*;

class StateCommitmentSpringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(StateCommitment.class);

    @Test
    void productionDefaultNeverActivatesV2() {
        runner.run(context -> {
            StateCommitment commitment = context.getBean(StateCommitment.class);
            assertFalse(commitment.isV2Active(0));
            assertFalse(commitment.isV2Active(Long.MAX_VALUE));
        });
    }

    @Test
    void activationHeightIsReadFromRealSpringProperty() {
        runner.withPropertyValues("nexus.core.state-commitment-v2.activation-height=100")
                .run(context -> {
                    StateCommitment commitment = context.getBean(StateCommitment.class);
                    assertFalse(commitment.isV2Active(99));
                    assertTrue(commitment.isV2Active(100));
                });
    }
}
