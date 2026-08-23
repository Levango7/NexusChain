package org.nexus.gateway.security;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * A request wrapper whose body can be read multiple times.
 *
 * <p>The HMAC signature verification ({@link RequestSignatureInterceptor}) must
 * read the raw request body before {@code @RequestBody} deserialization. Unlike
 * Spring's {@code ContentCachingRequestWrapper} — which only records bytes while
 * the stream is first consumed and therefore cannot be re-read — this wrapper
 * buffers the entire body up front and serves every subsequent
 * {@link #getInputStream()} call from the buffer.</p>
 */
public class RepeatableReadRequestWrapper extends HttpServletRequestWrapper {

    private final byte[] cachedBody;

    public RepeatableReadRequestWrapper(HttpServletRequest request) throws IOException {
        super(request);
        this.cachedBody = request.getInputStream().readAllBytes();
    }

    public byte[] getCachedBody() {
        return cachedBody;
    }

    public String getCachedBodyAsString() {
        return new String(cachedBody, StandardCharsets.UTF_8);
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream buffer = new ByteArrayInputStream(cachedBody);
        return new ServletInputStream() {
            @Override
            public int read() {
                return buffer.read();
            }

            @Override
            public boolean isFinished() {
                return buffer.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                throw new UnsupportedOperationException("Async read listeners are not supported");
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
}
