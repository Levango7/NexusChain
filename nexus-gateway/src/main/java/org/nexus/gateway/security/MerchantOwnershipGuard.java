package org.nexus.gateway.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Enforces merchant ownership on order/refund resources (P0-4 IDOR hardening).
 *
 * <p>{@link org.nexus.gateway.interceptor.ApiKeyInterceptor} authenticates the
 * caller and stores the merchant id in the {@code nexus.merchantId} request
 * attribute. Before this guard existed, only {@code RefundController} consumed
 * that attribute — every other endpoint operated on raw resource ids, letting
 * any verified merchant read or mutate any other merchant's orders.</p>
 *
 * <p>The guard fails closed: a missing or unparseable attribute (endpoint not
 * behind the interceptor, misconfigured exclusion, test without setup) is
 * treated as access denied, never as "no ownership check needed".</p>
 */
@Component
public class MerchantOwnershipGuard {

    /** Attribute name set by ApiKeyInterceptor after successful API key auth. */
    public static final String MERCHANT_ID_ATTR = "nexus.merchantId";

    /**
     * Resolve the authenticated caller's merchant id from the request attribute.
     *
     * @throws MerchantOwnershipException if the attribute is absent or unparseable
     */
    public Long requireMerchantId(HttpServletRequest request) {
        Object attr = request == null ? null : request.getAttribute(MERCHANT_ID_ATTR);
        Long merchantId = toLong(attr);
        if (merchantId == null) {
            throw new MerchantOwnershipException(
                    "Authenticated merchant context is required for this endpoint");
        }
        return merchantId;
    }

    /**
     * Verify the loaded resource belongs to the calling merchant.
     *
     * @param callerMerchantId authenticated caller (from {@link #requireMerchantId})
     * @param ownerMerchantId  merchant that owns the loaded resource
     * @param resourceType     resource kind for logs/responses ("order", "refund")
     * @param resourceId       resource id for logs/responses
     * @throws MerchantOwnershipException if the resource is not owned by the caller
     */
    public void requireOwned(Long callerMerchantId, Long ownerMerchantId,
                             String resourceType, Long resourceId) {
        if (callerMerchantId == null) {
            throw new MerchantOwnershipException(
                    "Authenticated merchant context is required for this endpoint");
        }
        if (ownerMerchantId == null || !callerMerchantId.equals(ownerMerchantId)) {
            // Response must not reveal who owns the resource; details go to server logs.
            throw new MerchantOwnershipException(
                    "Access denied: " + resourceType + " " + resourceId
                            + " does not belong to the authenticated merchant");
        }
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
