CREATE TABLE auth_login_throttle (
    fingerprint CHAR(64) PRIMARY KEY,
    failure_count INTEGER NOT NULL DEFAULT 0,
    window_started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    blocked_until TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_auth_login_throttle_cleanup ON auth_login_throttle(updated_at);
