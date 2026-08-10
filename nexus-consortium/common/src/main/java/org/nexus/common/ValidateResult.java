package org.nexus.common;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;

@Getter
@AllArgsConstructor
public class ValidateResult {
    private static final ValidateResult SUCCESS = new ValidateResult(true, "");

    public static ValidateResult success() {
        return SUCCESS;
    }

    public static ValidateResult fault(String reason) {
        return new ValidateResult(false, reason);
    }

    private boolean success;
    @NonNull
    private String reason;
}