package com.fonline.newdawn.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

@Component
public class RefreshCsrfFilter extends OncePerRequestFilter {
    public static final String COOKIE_NAME = "XSRF-TOKEN";
    public static final String HEADER_NAME = "X-XSRF-TOKEN";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !"POST".equals(request.getMethod())
                || !(uri.endsWith("/api/v1/auth/refresh") || uri.endsWith("/api/v1/auth/logout"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String cookie = request.getCookies() == null ? null : Arrays.stream(request.getCookies())
                .filter(value -> COOKIE_NAME.equals(value.getName())).map(Cookie::getValue).findFirst().orElse(null);
        String header = request.getHeader(HEADER_NAME);
        boolean valid = cookie != null && header != null && MessageDigest.isEqual(
                cookie.getBytes(StandardCharsets.UTF_8), header.getBytes(StandardCharsets.UTF_8));
        if (!valid) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"CSRF_FAILED\",\"message\":\"Refresh request could not be verified.\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
