package com.fonline.newdawn.auth;

import com.fonline.newdawn.config.AppProperties;
import com.fonline.newdawn.security.RefreshCsrfFilter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private static final String REFRESH_COOKIE = "refresh_token";
    private final AuthService authService;
    private final LoginThrottleService loginThrottle;
    private final AppProperties properties;

    public AuthController(AuthService authService, LoginThrottleService loginThrottle, AppProperties properties) {
        this.authService = authService;
        this.loginThrottle = loginThrottle;
        this.properties = properties;
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest,
                               HttpServletResponse response) {
        String remoteAddress = servletRequest.getRemoteAddr();
        loginThrottle.assertAllowed(remoteAddress, request.username());
        try {
            AuthService.Session session = authService.login(request.username(), request.password());
            loginThrottle.success(remoteAddress, request.username());
            setSessionCookies(response, session);
            return response(session);
        } catch (com.fonline.newdawn.common.ApiException exception) {
            if ("INVALID_CREDENTIALS".equals(exception.code())) {
                loginThrottle.failure(remoteAddress, request.username());
            }
            throw exception;
        }
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(HttpServletRequest request, HttpServletResponse response) {
        AuthService.Session session = authService.refresh(cookie(request, REFRESH_COOKIE));
        setSessionCookies(response, session);
        return response(session);
    }

    @PostMapping("/logout")
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        authService.logout(cookie(request, REFRESH_COOKIE));
        clearCookies(response);
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    private void setSessionCookies(HttpServletResponse response, AuthService.Session session) {
        ResponseCookie refresh = ResponseCookie.from(REFRESH_COOKIE, session.refreshToken())
                .httpOnly(true).secure(properties.cookies().secure()).sameSite(properties.cookies().sameSite())
                .path("/api/v1/auth").maxAge(Duration.between(Instant.now(), session.refreshExpiresAt())).build();
        ResponseCookie csrf = ResponseCookie.from(RefreshCsrfFilter.COOKIE_NAME, authService.csrfToken())
                .httpOnly(false).secure(properties.cookies().secure()).sameSite(properties.cookies().sameSite())
                .path("/").maxAge(Duration.between(Instant.now(), session.refreshExpiresAt())).build();
        response.addHeader(HttpHeaders.SET_COOKIE, refresh.toString());
        response.addHeader(HttpHeaders.SET_COOKIE, csrf.toString());
    }

    private void clearCookies(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true).secure(properties.cookies().secure()).sameSite(properties.cookies().sameSite())
                .path("/api/v1/auth").maxAge(Duration.ZERO).build().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(RefreshCsrfFilter.COOKIE_NAME, "")
                .httpOnly(false).secure(properties.cookies().secure()).sameSite(properties.cookies().sameSite())
                .path("/").maxAge(Duration.ZERO).build().toString());
    }

    private String cookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        return Arrays.stream(request.getCookies()).filter(cookie -> name.equals(cookie.getName()))
                .map(Cookie::getValue).findFirst().orElse(null);
    }

    private TokenResponse response(AuthService.Session session) {
        return new TokenResponse(session.accessToken(), "Bearer", session.accessExpiresAt(), session.user());
    }

    public record LoginRequest(
            @NotBlank @Size(min = 3, max = 80) String username,
            @NotBlank @Size(min = 12, max = 128) String password
    ) {}
    public record TokenResponse(String accessToken, String tokenType, Instant expiresAt, AuthService.SessionUser user) {}
}
