# Wiki backup and restore

The central administrator's **Backup** tab creates a versioned ZIP archive containing:

- categories and subcategories;
- articles, publication state and every revision;
- article relations and revision-to-asset links;
- every completed wiki asset, including unlinked files;
- editor accounts, including their Argon2 password hashes and enabled state.

Administrator accounts, sessions, audit logs, client releases and updater files are not part of this backup. Passwords are not stored as plain text, but the archive remains confidential because the imported editor passwords stay valid.

## Restore rules

Restore is available only to the central administrator. The API locks the relevant database tables and accepts an import only if all these counts are zero:

- wiki categories;
- wiki articles and revisions;
- media assets;
- accounts with the `EDITOR` role.

The bootstrap administrator and site configuration may already exist. Source administrator identifiers are mapped to the administrator performing the import, while imported editor authors retain their original identifiers.

Before any database writes, the backend validates the archive format, paths, identifier graph, record limits, asset sizes and every asset SHA-256 checksum. Restored storage objects are removed automatically if the database transaction rolls back.

## Reverse proxy

Spring accepts backup archives up to `BACKUP_MAX_FILE_SIZE`, which defaults to `2GB`. The same limit must be allowed on the main `fonline-nd.com` reverse proxy. The private backend port remains unexposed.

Example directives for the main HTTPS virtual host:

```nginx
location /api/v1/admin/backup/ {
    client_max_body_size 2g;
    proxy_request_buffering off;
    proxy_buffering off;
    proxy_read_timeout 3600s;
    proxy_send_timeout 3600s;

    proxy_pass http://newdawn_api;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Host $host;
    proxy_set_header X-Forwarded-Proto https;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
}
```

Keep the general `/api/` proxy in place for all other application endpoints. Do not expose backup endpoints on `updater.fonline-nd.com`.
