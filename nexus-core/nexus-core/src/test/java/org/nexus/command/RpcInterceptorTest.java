package org.nexus.command;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RpcInterceptorTest {

    private boolean accepts(String configured, String supplied) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("token")).thenReturn(supplied);
        return new RpcInterceptor(configured).preHandle(
                request, mock(HttpServletResponse.class), new Object());
    }

    @Test
    void rejectsMissingConfiguration() {
        assertFalse(accepts(null, "test-only-token"));
        assertFalse(accepts("", "test-only-token"));
        assertFalse(accepts("", ""));
    }

    @Test
    void rejectsMissingRequestToken() {
        assertFalse(accepts("test-only-token", null));
        assertFalse(accepts("test-only-token", ""));
    }

    @Test
    void acceptsConfiguredTokenByContent() {
        assertTrue(accepts("test-only-token", new String("test-only-token")));
    }

    @Test
    void rejectsDifferentToken() {
        assertFalse(accepts("test-only-token", "test-only-tokem"));
        assertFalse(accepts("test-only-token", "test-only-token-extra"));
        assertFalse(accepts("test-only-token", "short"));
    }

    @Test
    void usesCurrentConfigurationAfterRotation() {
        assertFalse(accepts("test-only-new-token", "test-only-old-token"));
        assertTrue(accepts("test-only-new-token", "test-only-new-token"));
    }
}
