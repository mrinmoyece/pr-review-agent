package com.agentforge.prreview.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Enforces the webhook body limit while the servlet container reads the stream.
 */
@Component
public class WebhookPayloadLimitFilter extends OncePerRequestFilter {

    @Value("${review.webhook.max-payload-bytes:10485760}")
    private int maxPayloadBytes;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod())
                || !normalizedApplicationPath(request).equals("/webhook/github");
    }

    private String normalizedApplicationPath(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        String applicationPath = requestUri.startsWith(contextPath)
                ? requestUri.substring(contextPath.length()) : requestUri;
        return java.util.Arrays.stream(applicationPath.split("/", -1))
                .map(segment -> {
                    int matrixStart = segment.indexOf(';');
                    return matrixStart >= 0 ? segment.substring(0, matrixStart) : segment;
                })
                .collect(java.util.stream.Collectors.joining("/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxPayloadBytes) {
            reject(response);
            return;
        }
        HttpServletRequest bounded = new HttpServletRequestWrapper(request) {
            @Override
            public ServletInputStream getInputStream() throws IOException {
                return new LimitedServletInputStream(super.getInputStream(), maxPayloadBytes);
            }
        };
        try {
            filterChain.doFilter(bounded, response);
        } catch (Exception e) {
            if (hasPayloadLimitCause(e)) {
                reject(response);
                return;
            }
            if (e instanceof IOException ioException) {
                throw ioException;
            }
            if (e instanceof ServletException servletException) {
                throw servletException;
            }
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new ServletException(e);
        }
    }

    private boolean hasPayloadLimitCause(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof PayloadTooLargeException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.reset();
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"Payload too large\"}");
    }

    private static final class LimitedServletInputStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final long limit;
        private long consumed;

        private LimitedServletInputStream(ServletInputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                consume(1);
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = delegate.read(bytes, offset, length);
            if (count > 0) {
                consume(count);
            }
            return count;
        }

        private void consume(int count) throws PayloadTooLargeException {
            consumed += count;
            if (consumed > limit) {
                throw new PayloadTooLargeException();
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }
    }

    private static final class PayloadTooLargeException extends IOException {
        private PayloadTooLargeException() {
            super("Webhook payload exceeded the configured limit");
        }
    }
}
