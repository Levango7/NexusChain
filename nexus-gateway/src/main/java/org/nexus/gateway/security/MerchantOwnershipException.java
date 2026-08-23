package org.nexus.gateway.security;

/**
 * Thrown when a caller attempts to access or mutate a resource that belongs to
 * a different merchant (P0-4 IDOR hardening).
 *
 * <p>Mapped to HTTP 403 by {@code GlobalExceptionHandler} (v1 envelope) and
 * {@code V2ExceptionHandler} (v2 envelope). The message intentionally omits
 * the owning merchant's identity; server-side logs carry the details.</p>
 */
public class MerchantOwnershipException extends RuntimeException {

    public MerchantOwnershipException(String message) {
        super(message);
    }
}
