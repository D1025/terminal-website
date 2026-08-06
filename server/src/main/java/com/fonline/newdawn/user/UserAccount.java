package com.fonline.newdawn.user;

import java.time.Instant;
import java.util.UUID;

public record UserAccount(
        UUID id,
        String username,
        String passwordHash,
        Role role,
        boolean enabled,
        boolean centralAdmin,
        Instant createdAt
) {}
