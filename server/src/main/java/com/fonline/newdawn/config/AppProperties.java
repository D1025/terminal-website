package com.fonline.newdawn.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("app")
public record AppProperties(
        String publicBaseUrl,
        String allowedOrigins,
        Jwt jwt,
        Cookies cookies,
        BootstrapAdmin bootstrapAdmin,
        Storage storage
) {
    public record Jwt(String issuer, String audience, String secret, Duration accessTtl, Duration refreshTtl) {}

    public record Cookies(boolean secure, String sameSite) {}

    public record BootstrapAdmin(String username, String password) {}

    public record Storage(
            String endpoint,
            String publicEndpoint,
            String region,
            String bucket,
            String accessKey,
            String secretKey,
            boolean pathStyle,
            Duration uploadUrlTtl,
            Duration downloadUrlTtl
    ) {}
}
