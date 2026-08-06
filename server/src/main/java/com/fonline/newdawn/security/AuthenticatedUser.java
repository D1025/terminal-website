package com.fonline.newdawn.security;

import com.fonline.newdawn.user.Role;

import java.util.UUID;

public record AuthenticatedUser(UUID id, String username, Role role) {}
