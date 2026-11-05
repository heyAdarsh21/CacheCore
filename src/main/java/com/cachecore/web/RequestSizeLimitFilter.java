package com.cachecore.web;

import com.cachecore.engine.CacheLimits;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Rejects cache requests whose declared body is larger than the service limit
 * before the JSON body is parsed. Chunked requests without a Content-Length are
 * still bounded by the value-length validation and the servlet container.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long contentLength = request.getContentLengthLong();
        if (request.getRequestURI().startsWith("/api/v1/cache")
                && contentLength > CacheLimits.MAX_REQUEST_BYTES) {
            response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("""
                    {"type":"about:blank","title":"Payload Too Large","status":413,\
                    "detail":"Request body exceeds the 2 MiB limit","code":"REQUEST_TOO_LARGE"}""");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
