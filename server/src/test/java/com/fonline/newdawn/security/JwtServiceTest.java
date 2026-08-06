package com.fonline.newdawn.security;

import com.fonline.newdawn.config.AppProperties;
import com.fonline.newdawn.user.Role;
import com.fonline.newdawn.user.UserAccount;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {
    @Test
    void issuesAndValidatesAccessTokenProfile() {
        AppProperties properties = new AppProperties("http://localhost:5173", "http://localhost:5173",
                new AppProperties.Jwt("https://newdawn.local", "new-dawn-api",
                        "test-secret-that-is-at-least-sixty-four-bytes-long-and-never-used-outside-tests-123456",
                        Duration.ofMinutes(10), Duration.ofDays(30)),
                new AppProperties.Cookies(false, "Strict"),
                new AppProperties.BootstrapAdmin("admin", "irrelevant-password"),
                new AppProperties.Storage("http://localhost:9000", "http://localhost:9000", "us-east-1",
                        "bucket", "key", "secret", true, Duration.ofMinutes(15), Duration.ofMinutes(15)));
        JwtService service = new JwtService(properties);
        service.configure();
        UUID id = UUID.randomUUID();
        UserAccount user = new UserAccount(id, "admin", "hash", Role.ADMIN, true, true, Instant.now());

        JwtService.IssuedToken issued = service.issue(user);
        var decoded = service.decode(issued.value());

        assertThat(decoded.getSubject()).isEqualTo(id.toString());
        assertThat(decoded.getHeaders().get("typ").toString()).isEqualTo("at+jwt");
        assertThat(decoded.getAudience()).containsExactly("new-dawn-api");
        assertThat(decoded.getClaimAsStringList("roles")).containsExactly("ADMIN");
    }
}
