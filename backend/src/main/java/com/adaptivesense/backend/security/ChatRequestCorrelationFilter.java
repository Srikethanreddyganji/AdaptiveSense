package com.adaptivesense.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/** Assigns a server-generated, per-request correlation ID to chat requests. */
@Component
public class ChatRequestCorrelationFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_ATTRIBUTE = ChatRequestCorrelationFilter.class.getName() + ".requestId";
    public static final String REQUEST_ID_HEADER = "X-Request-ID";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return !"/api/chat".equals(path) && !path.startsWith("/api/chat/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        MDC.put("request_id", requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("request_id");
        }
    }
}
