package com.fonline.newdawn.user;

import com.fonline.newdawn.config.AppProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AdminBootstrap implements ApplicationRunner {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final AppProperties properties;

    public AdminBootstrap(UserRepository users, PasswordEncoder passwords, AppProperties properties) {
        this.users = users;
        this.passwords = passwords;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.centralAdminExists()) return;
        String username = properties.bootstrapAdmin().username();
        String password = properties.bootstrapAdmin().password();
        if (username == null || username.trim().length() < 3 || password == null || password.length() < 16) {
            throw new IllegalStateException("Bootstrap admin username and a password of at least 16 characters are required.");
        }
        users.create(username, passwords.encode(password), Role.ADMIN, true, null);
    }
}
