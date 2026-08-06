package com.fonline.newdawn.auth;

import java.time.Instant;
import java.util.UUID;

public record RefreshTokenRecord(
        UUID id,
        UUID userId,
        UUID familyId,
        Instant expiresAt,
        Instant usedAt,
        Instant revokedAt
) {}
