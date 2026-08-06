INSERT INTO site_configuration(key, value) VALUES
    ('wikiUnlockAt', '"2026-09-09T00:00:00+02:00"'::jsonb),
    ('downloadUnlockAt', '"2026-09-09T00:00:00+02:00"'::jsonb),
    ('trailerUnlockAt', '"2026-09-04T00:00:00+02:00"'::jsonb),
    ('trailerYoutubeUrl', '""'::jsonb)
ON CONFLICT (key) DO NOTHING;
