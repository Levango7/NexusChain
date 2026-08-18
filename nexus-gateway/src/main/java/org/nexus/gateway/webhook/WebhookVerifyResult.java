package org.nexus.gateway.webhook;

/**
 * Result of a webhook signature verification.
 *
 * <p>Carries a boolean {@code valid} flag plus a human-readable reason that can be
 * logged or returned to the caller. The reason is intentionally non-sensitive —
 * it never contains the secret or signature bytes, only diagnostic labels.</p>
 */
public final class WebhookVerifyResult {

    private final boolean valid;
    private final String reason;

    private WebhookVerifyResult(boolean valid, String reason) {
        this.valid = valid;
        this.reason = reason;
    }

    public static WebhookVerifyResult ok() {
        return new WebhookVerifyResult(true, "OK");
    }

    public static WebhookVerifyResult fail(String reason) {
        return new WebhookVerifyResult(false, reason);
    }

    public boolean isValid() { return valid; }
    public String getReason() { return reason; }
}