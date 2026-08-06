package com.fonline.newdawn.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Profile("prod")
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ProductionHttpsFilter extends OncePerRequestFilter {
    private static final int HTTPS_REQUIRED = 426;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/api/") && !request.isSecure()) {
            response.setStatus(HTTPS_REQUIRED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write("{\"code\":\"HTTPS_REQUIRED\",\"message\":\"This API accepts protected traffic only over HTTPS.\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
