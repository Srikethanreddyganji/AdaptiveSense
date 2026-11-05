package com.adaptivesense.backend.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class ChatRequestCorrelationFilterTest {

    @Test
    void serverGeneratedRequestIdIsAvailableToControllerAndReturnedInHeader() throws Exception {
        ChatRequestCorrelationFilter filter = new ChatRequestCorrelationFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat");
        request.setServletPath("/api/chat");
        request.addHeader("X-Request-ID", "client-supplied-id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = (req, res) -> {
            String attribute = (String) req.getAttribute(ChatRequestCorrelationFilter.REQUEST_ID_ATTRIBUTE);
            assertNotNull(attribute);
            assertNotEquals("client-supplied-id", attribute);
            assertEquals(attribute, MDC.get("request_id"));
        };

        filter.doFilter(request, response, chain);

        assertEquals(request.getAttribute(ChatRequestCorrelationFilter.REQUEST_ID_ATTRIBUTE),
                response.getHeader(ChatRequestCorrelationFilter.REQUEST_ID_HEADER));
        assertNull(MDC.get("request_id"));
    }

    @Test
    void doesNotAddCorrelationMetadataOutsideChatRoutes() throws Exception {
        ChatRequestCorrelationFilter filter = new ChatRequestCorrelationFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/history");
        request.setServletPath("/api/history");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertNull(request.getAttribute(ChatRequestCorrelationFilter.REQUEST_ID_ATTRIBUTE));
        assertNull(response.getHeader(ChatRequestCorrelationFilter.REQUEST_ID_HEADER));
    }
}
