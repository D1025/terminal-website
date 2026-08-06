CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE app_user (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(80) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL CHECK (role IN ('ADMIN', 'EDITOR')),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    central_admin BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID REFERENCES app_user(id),
    CONSTRAINT username_not_blank CHECK (length(trim(username)) >= 3)
);

CREATE UNIQUE INDEX uk_app_user_username_lower ON app_user (lower(username));
CREATE UNIQUE INDEX uk_single_central_admin ON app_user (central_admin) WHERE central_admin = TRUE;

CREATE TABLE refresh_token (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    family_id UUID NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_refresh_token_user ON refresh_token(user_id);
CREATE INDEX idx_refresh_token_family ON refresh_token(family_id);

CREATE TABLE wiki_category (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_id UUID REFERENCES wiki_category(id) ON DELETE SET NULL,
    slug VARCHAR(120) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    description TEXT,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE wiki_page_type (
    key VARCHAR(60) PRIMARY KEY,
    display_name VARCHAR(120) NOT NULL,
    description TEXT,
    property_schema JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE wiki_page (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug VARCHAR(220) NOT NULL UNIQUE,
    page_type VARCHAR(60) NOT NULL REFERENCES wiki_page_type(key),
    category_id UUID REFERENCES wiki_category(id) ON DELETE SET NULL,
    locale VARCHAR(12) NOT NULL DEFAULT 'en',
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    published_revision_id UUID,
    search_vector TSVECTOR,
    created_by UUID NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    lock_version INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE wiki_revision (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    page_id UUID NOT NULL REFERENCES wiki_page(id) ON DELETE CASCADE,
    revision_number INTEGER NOT NULL,
    title VARCHAR(220) NOT NULL,
    summary TEXT,
    content_markdown TEXT NOT NULL,
    properties JSONB NOT NULL DEFAULT '{}'::jsonb,
    change_note VARCHAR(500),
    created_by UUID NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(page_id, revision_number)
);

ALTER TABLE wiki_page
    ADD CONSTRAINT fk_wiki_page_published_revision
    FOREIGN KEY (published_revision_id) REFERENCES wiki_revision(id) ON DELETE SET NULL;

CREATE INDEX idx_wiki_page_category ON wiki_page(category_id);
CREATE INDEX idx_wiki_page_type ON wiki_page(page_type);
CREATE INDEX idx_wiki_page_status ON wiki_page(status);
CREATE INDEX idx_wiki_page_search ON wiki_page USING GIN(search_vector);
CREATE INDEX idx_wiki_revision_page ON wiki_revision(page_id, revision_number DESC);

CREATE TABLE wiki_revision_relation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    revision_id UUID NOT NULL REFERENCES wiki_revision(id) ON DELETE CASCADE,
    target_page_id UUID NOT NULL REFERENCES wiki_page(id) ON DELETE RESTRICT,
    relation_type VARCHAR(80) NOT NULL,
    label VARCHAR(180),
    sort_order INTEGER NOT NULL DEFAULT 0,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE(revision_id, target_page_id, relation_type)
);

CREATE INDEX idx_wiki_relation_revision ON wiki_revision_relation(revision_id);
CREATE INDEX idx_wiki_relation_target ON wiki_revision_relation(target_page_id);

CREATE TABLE media_asset (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kind VARCHAR(20) NOT NULL CHECK (kind IN ('WIKI_IMAGE', 'WIKI_FILE')),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'READY', 'REJECTED')),
    object_key VARCHAR(500) NOT NULL UNIQUE,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(160) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
    sha256 CHAR(64),
    alt_text VARCHAR(500),
    uploaded_by UUID NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE wiki_revision_asset (
    revision_id UUID NOT NULL REFERENCES wiki_revision(id) ON DELETE CASCADE,
    asset_id UUID NOT NULL REFERENCES media_asset(id) ON DELETE RESTRICT,
    usage VARCHAR(40) NOT NULL DEFAULT 'INLINE',
    caption VARCHAR(500),
    sort_order INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY(revision_id, asset_id)
);

CREATE TABLE client_release (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version VARCHAR(80) NOT NULL,
    platform VARCHAR(40) NOT NULL DEFAULT 'WINDOWS',
    channel VARCHAR(20) NOT NULL DEFAULT 'STABLE' CHECK (channel IN ('STABLE', 'TEST')),
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'UPLOADING', 'UPLOADED', 'PUBLISHED', 'RETIRED')),
    file_name VARCHAR(255) NOT NULL,
    object_key VARCHAR(500) NOT NULL UNIQUE,
    content_type VARCHAR(160) NOT NULL DEFAULT 'application/octet-stream',
    size_bytes BIGINT NOT NULL DEFAULT 0 CHECK (size_bytes >= 0),
    sha256 CHAR(64),
    release_notes_markdown TEXT,
    minimum_launcher_version VARCHAR(80),
    created_by UUID NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    UNIQUE(version, platform, channel)
);

CREATE INDEX idx_client_release_public ON client_release(status, channel, platform, published_at DESC);

CREATE TABLE download_stat_daily (
    release_id UUID NOT NULL REFERENCES client_release(id) ON DELETE CASCADE,
    day DATE NOT NULL,
    download_count BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY(release_id, day)
);

CREATE TABLE site_configuration (
    key VARCHAR(120) PRIMARY KEY,
    value JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES app_user(id)
);

CREATE TABLE audit_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id UUID REFERENCES app_user(id) ON DELETE SET NULL,
    action VARCHAR(120) NOT NULL,
    entity_type VARCHAR(80) NOT NULL,
    entity_id UUID,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_log_created ON audit_log(created_at DESC);
CREATE INDEX idx_audit_log_actor ON audit_log(actor_id);

INSERT INTO wiki_page_type(key, display_name, description, property_schema) VALUES
    ('ARTICLE', 'Article', 'General wiki article', '{"type":"object","additionalProperties":true}'),
    ('WEAPON', 'Weapon', 'Weapon statistics and description', '{"type":"object","additionalProperties":true}'),
    ('PERK', 'Perk', 'Perk requirements and effects', '{"type":"object","additionalProperties":true}'),
    ('CLASS', 'Class', 'Playable class description', '{"type":"object","additionalProperties":true}'),
    ('ITEM', 'Item', 'Inventory or world item', '{"type":"object","additionalProperties":true}'),
    ('LOCATION', 'Location', 'World location', '{"type":"object","additionalProperties":true}'),
    ('NPC', 'NPC', 'Non-player character', '{"type":"object","additionalProperties":true}'),
    ('QUEST', 'Quest', 'Quest flow and requirements', '{"type":"object","additionalProperties":true}'),
    ('MECHANIC', 'Mechanic', 'Game mechanic', '{"type":"object","additionalProperties":true}');

INSERT INTO site_configuration(key, value) VALUES
    ('launchAt', '"2026-09-11T00:00:00+02:00"'::jsonb),
    ('serverStatus', '"ONLINE"'::jsonb)
ON CONFLICT (key) DO NOTHING;
