package org.nexus.gateway.escrow;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EscrowControllerMonetaryTest {

    @Test
    void bigIntegerAmountDoesNotPassThroughDouble() {
        EscrowController controller = new EscrowController(null, null);
        BigInteger value = BigInteger.valueOf(9_007_199_254_740_993L);
        BigDecimal converted = ReflectionTestUtils.invokeMethod(controller, "toBigDecimal", value);
        assertEquals("9007199254740993", converted.toPlainString());
    }

    @Test
    void tinyDecimalDoesNotCollapseToZero() {
        EscrowController controller = new EscrowController(null, null);
        BigDecimal value = new BigDecimal("1e-46");
        BigDecimal converted = ReflectionTestUtils.invokeMethod(controller, "toBigDecimal", value);
        assertEquals(0, value.compareTo(converted), converted.toPlainString());
    }
}
