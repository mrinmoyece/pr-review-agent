package com.agentforge.prreview.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookPayloadLimitFilterTest {

    private WebhookPayloadLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new WebhookPayloadLimitFilter();
        ReflectionTestUtils.setField(filter, "maxPayloadBytes", 5);
    }

    @Test
    void rejectsKnownOversizedContentBeforeChain() throws Exception {
        MockHttpServletRequest request = request("123456");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            throw new AssertionError("chain must not run");
        });

        assertThat(response.getStatus()).isEqualTo(413);
    }

    @Test
    void rejectsChunkedBodyWhileItIsRead() throws Exception {
        MockHttpServletRequest delegate = request("123456");
        HttpServletRequest request = new HttpServletRequestWrapper(delegate) {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (boundedRequest, ignoredResponse) -> {
            ServletInputStream input = boundedRequest.getInputStream();
            while (input.read() >= 0) {
                // Consume the streamed request body.
            }
        };

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
    }

    private MockHttpServletRequest request(String content) throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/webhook/github");
        request.setContent(content.getBytes());
        return request;
    }
}
