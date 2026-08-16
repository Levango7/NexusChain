package org.nexus.consortium.consensus.poa;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PoaValidator 单元测试。
 * 覆盖 Validator 接口实现。
 */
public class PoaValidatorTest {

    @Test
    public void testImplementsValidator() {
        PoaValidator validator = new PoaValidator();
        assertNotNull(validator);
        assertTrue(validator instanceof org.nexus.common.ConsensusEngine.Validator);
    }

    @Test
    public void testValidateTransactionWithWrongVersion() {
        PoaValidator validator = new PoaValidator();
        org.nexus.common.Transaction tx = org.nexus.common.Transaction.builder()
                .blockHash(new org.nexus.common.HexBytes(new byte[]{1}))
                .height(1L)
                .version(0)
                .type(0)
                .createdAt(100L)
                .nonce(1L)
                .from(new org.nexus.common.HexBytes(new byte[]{2}))
                .gasPrice(0L)
                .amount(100L)
                .payload(new org.nexus.common.HexBytes(new byte[]{3}))
                .to(new org.nexus.common.HexBytes(new byte[]{4}))
                .signature(new org.nexus.common.HexBytes(new byte[]{5}))
                .hash(new org.nexus.common.HexBytes(new byte[]{6}))
                .build();
        org.nexus.common.ValidateResult result = validator.validate(tx);
        assertNotNull(result);
        assertFalse(result.isSuccess());
    }
}