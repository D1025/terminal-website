CREATE TABLE update_release (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version VARCHAR(80) NOT NULL,
    channel VARCHAR(20) NOT NULL DEFAULT 'STABLE' CHECK (channel IN ('STABLE', 'TEST')),
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'UPLOADING', 'PUBLISHED', 'RETIRED')),
    base_release_id UUID REFERENCES update_release(id) ON DELETE RESTRICT,
    release_notes_markdown TEXT,
    minimum_launcher_version VARCHAR(80),
    game_server_host VARCHAR(253),
    game_server_port INTEGER CHECK (game_server_port BETWEEN 1 AND 65535),
    created_by UUID NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    UNIQUE(version, channel)
);

CREATE UNIQUE INDEX uk_update_release_published_channel
    ON update_release(channel) WHERE status = 'PUBLISHED';
CREATE INDEX idx_update_release_history
    ON update_release(channel, created_at DESC);

CREATE TABLE update_file (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    release_id UUID NOT NULL REFERENCES update_release(id) ON DELETE CASCADE,
    target_path VARCHAR(500) NOT NULL,
    path_key VARCHAR(500) NOT NULL,
    action VARCHAR(20) NOT NULL CHECK (action IN ('UPSERT', 'DELETE')),
    overwrite_policy VARCHAR(20) NOT NULL DEFAULT 'REPLACE' CHECK (overwrite_policy IN ('REPLACE', 'PRESERVE')),
    upload_status VARCHAR(20) NOT NULL CHECK (upload_status IN ('PENDING', 'READY')),
    object_key VARCHAR(700),
    file_name VARCHAR(255),
    content_type VARCHAR(160),
    size_bytes BIGINT NOT NULL DEFAULT 0 CHECK (size_bytes >= 0),
    sha256 CHAR(64),
    legacy_crc32 INTEGER,
    inherited BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(release_id, path_key),
    CHECK (
        (action = 'DELETE' AND upload_status = 'READY' AND object_key IS NULL AND file_name IS NULL
            AND content_type IS NULL AND size_bytes = 0 AND sha256 IS NULL)
        OR
        (action = 'UPSERT' AND object_key IS NOT NULL AND file_name IS NOT NULL AND content_type IS NOT NULL
            AND size_bytes > 0 AND (upload_status = 'PENDING' OR sha256 IS NOT NULL))
    )
);

CREATE INDEX idx_update_file_manifest ON update_file(release_id, path_key);
CREATE INDEX idx_update_file_object ON update_file(object_key) WHERE object_key IS NOT NULL;

CREATE TABLE update_download_stat_daily (
    release_id UUID NOT NULL REFERENCES update_release(id) ON DELETE CASCADE,
    day DATE NOT NULL,
    file_download_count BIGINT NOT NULL DEFAULT 0,
    bytes_requested BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY(release_id, day)
);
