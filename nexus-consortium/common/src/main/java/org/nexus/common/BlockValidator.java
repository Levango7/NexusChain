package org.nexus.common;

public interface BlockValidator {
    ValidateResult validate(Block block, Block dependency);
}
