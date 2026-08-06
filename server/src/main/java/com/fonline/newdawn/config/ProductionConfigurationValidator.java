package com.fonline.newdawn.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URI;

@Component
@Profile("prod")
public class ProductionConfigurationValidator implements ApplicationRunner {
    private final AppProperties properties;

    public ProductionConfigurationValidator(AppProperties properties) {
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        require(properties.cookies().secure(), "Production refresh cookies must be Secure.");
        require(properties.jwt().secret() != null && properties.jwt().secret().length() >= 64
                        && !properties.jwt().secret().startsWith("dev-only"),
                "Production JWT_SECRET must be a unique value with at least 64 characters.");
        require(properties.bootstrapAdmin().password() != null
                        && !properties.bootstrapAdmin().password().startsWith("ChangeThis"),
                "Production bootstrap admin password must be replaced.");
        require(properties.storage().accessKey() != null && !"newdawn".equals(properties.storage().accessKey()),
                "Production S3 access key must be replaced.");
        require(properties.storage().secretKey() != null && properties.storage().secretKey().length() >= 16
                        && !properties.storage().secretKey().startsWith("newdawn_"),
                "Production S3 secret key must be a strong unique value.");
        requireHttps(properties.publicBaseUrl(), "PUBLIC_BASE_URL");
        requireHttps(properties.storage().publicEndpoint(), "S3_PUBLIC_ENDPOINT");
        for (String origin : properties.allowedOrigins().split(",")) requireHttps(origin.trim(), "ALLOWED_ORIGINS");
    }

    private void requireHttps(String value, String name) {
        URI uri = URI.create(value);
        require("https".equalsIgnoreCase(uri.getScheme()), name + " must use HTTPS in production.");
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
