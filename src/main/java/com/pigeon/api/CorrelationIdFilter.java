package com.pigeon.api;

import com.pigeon.support.CorrelationId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs first on every HTTP request: takes the caller's X-Correlation-Id (if safe) or creates one,
 * puts it on the log context for the whole request, and returns it in the response header, on
 * errors too. Controllers read it with {@link CorrelationId#current()}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = CorrelationId.resolve(request.getHeader(CorrelationId.HEADER));
        CorrelationId.bind(id, null);
        response.setHeader(CorrelationId.HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            CorrelationId.clear();
        }
    }
}
