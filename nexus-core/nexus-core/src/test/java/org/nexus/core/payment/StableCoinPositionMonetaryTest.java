package org.nexus.core.payment;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StableCoinPositionMonetaryTest {

    @Test
    void mintDoesNotOverflowWhenComputingRatioAndLiquidationPrice() {
        StableCoinPosition position = new StableCoinPosition();
        position.mint(Long.MAX_VALUE, Long.MAX_VALUE, 100);
        assertEquals(100, position.getCollateralRatio());
        assertEquals(1L, position.getLiquidationPrice());
    }

    @Test
    void calculateRatioDoesNotOverflowBeforeDivision() {
        StableCoinPosition position = new StableCoinPosition();
        position.setCollateralAmount(Long.MAX_VALUE);
        position.setMintedAmount(Long.MAX_VALUE);
        assertEquals(200, position.calculateCollateralRatio(2));
    }

    @Test
    void collateralValueOverflowFailsClosed() {
        StableCoinPosition position = new StableCoinPosition();
        position.setCollateralAmount(Long.MAX_VALUE);
        assertThrows(ArithmeticException.class, () -> position.getCollateralValue(2));
    }

    @Test
    void addCollateralOverflowFailsClosed() {
        StableCoinPosition position = new StableCoinPosition();
        position.setCollateralAmount(Long.MAX_VALUE);
        position.setCollateralRatio(100);
        assertThrows(ArithmeticException.class, () -> position.addCollateral(1));
    }

    @Test
    void liquidationUsesExactIntegerArithmetic() {
        StableCoinPosition position = new StableCoinPosition();
        position.setCollateralAmount(Long.MAX_VALUE);
        position.setState(StableCoinPosition.State.LIQUIDATABLE);
        position.liquidate(1);
        BigDecimal expected = BigDecimal.valueOf(Long.MAX_VALUE)
                .multiply(BigDecimal.valueOf(99))
                .divide(BigDecimal.valueOf(100), 0, java.math.RoundingMode.DOWN);
        assertEquals(expected.longValue(), position.getCollateralAmount());
    }
}
