package org.nexus.db;

import org.junit.jupiter.api.Test;
import org.nexus.consensus.pos.Validator;
import org.nexus.consensus.pos.ValidatorRegistry;
import org.nexus.core.Block;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** PoS 确认门槛：自举可确认与多数派安全边界同时成立，不能为消除停顿而降低 2/3。 */
class StateDBLeastConfirmsTest {

    @Test
    void singleActiveValidatorNeedsOneAndThreeNeedTwo() {
        StateDB stateDB = new StateDB(100, -1, 1, -1, 1);
        ValidatorRegistry registry = mock(ValidatorRegistry.class);
        ReflectionTestUtils.setField(stateDB, "consensusMode", "pos");
        ReflectionTestUtils.setField(stateDB, "validatorRegistry", registry);
        Block block = new Block();

        when(registry.getActiveValidators()).thenReturn(List.of(mock(Validator.class)));
        Integer single = ReflectionTestUtils.invokeMethod(stateDB, "computeLeastConfirms", block);
        assertEquals(1, single);

        when(registry.getActiveValidators()).thenReturn(List.of(
                mock(Validator.class), mock(Validator.class), mock(Validator.class)));
        Integer majority = ReflectionTestUtils.invokeMethod(stateDB, "computeLeastConfirms", block);
        assertEquals(2, majority);
    }
}
