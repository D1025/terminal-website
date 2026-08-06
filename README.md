# FOnline: New Dawn — terminal, wiki, and client distribution

The project consists of a terminal-inspired React frontend and a modular Spring Boot backend. PostgreSQL stores users, wiki revisions, relationships, the search index, and release metadata. S3 stores images, attachments, and client packages; MinIO provides the local S3-compatible environment.

The single-Droplet production stack uses local MinIO, automatic HTTPS, local
PostgreSQL/MinIO backups, retention, and disk monitoring. See
[`docs/production-deployment.md`](docs/production-deployment.md).

## Getting started

Node.js, Java 21, Maven, and Docker are required.

```powershell
Copy-Item .env.example .env
docker compose --profile full up --build -d
npm install
npm run dev
```

Frontend: `http://localhost:5173`

API: `http://localhost:8080/api/v1`

MinIO Console: `http://localhost:9001`

The default local central administrator credentials are `admin` / `ChangeThisAdminPassword123!`. They are intended only for local development. Replace every secret from `.env.example` before deployment.

To run the backend outside Docker:

```powershell
docker compose up -d postgres minio minio-init
Set-Location server
mvn spring-boot:run
```

## Permissions

| Operation | Public | Editor | Central admin |
|---|---:|---:|---:|
| Browse and search the wiki | yes | yes | yes |
| Wiki revisions, publishing, and media | no | yes | yes |
| Object types and categories | no | no | yes |
| Editor accounts | no | no | yes |
| Client upload and publishing | no | no | yes |
| Site configuration | no | no | yes |

The database allows at most one `central_admin` account. The user endpoint can create only the `EDITOR` role, and there is no endpoint for creating another administrator.

## Wiki

The wiki supports:

- arbitrary object types with fields described by JSON Schema;
- Markdown content, including headings, lists, tables, code, links, and images;
- hierarchical categories;
- object properties stored in JSONB;
- directional relationships and dependencies between pages;
- images, galleries, and attachments stored in S3;
- immutable revisions, publication of a selected revision, and optimistic editing locks;
- public PostgreSQL full-text search with ranking and excerpts;
- public read access without an account.

Embedded HTML is not rendered. Images are limited to PNG, JPEG, WebP, GIF, and AVIF, which excludes active SVG content.

The editor's **Table** tool inserts a standard GitHub-Flavored Markdown table with a configurable number of columns (1-12) and total rows (2-30, including the header). The **Columns** tool inserts a safe layout block:

```markdown
:::columns
:::left
Main article text.
:::right
| Attribute | Value |
| --- | --- |
| Damage | 10 |
:::end

This content returns to the full page width.
```

Both columns support Markdown. The right column is intended for a compact table or short reference information. The layout stacks vertically on small screens.

## Game updates

The administration console contains two separate distribution modules:

- **Client files** publishes complete downloadable client packages;
- **Updates** publishes file-level desired-state manifests consumed by the launcher.

An update draft inherits the currently published manifest in its channel. Administrators upload only changed files, optionally mark existing paths as `PRESERVE`, or add explicit `DELETE` entries. Uploads go directly to S3-compatible storage; the API streams each completed object from private storage once to calculate SHA-256 and the legacy launcher CRC before it can be published. Publishing atomically retires the previous manifest, and any retired manifest can be reactivated as a rollback.

The launcher API base is `https://updater.fonline-nd.com/api/v1`. The public reverse proxy exposes only the manifest and file redirect endpoints on this host. Spring Boot remains private on port `8080`, while file bodies are downloaded from the HTTPS S3/CDN endpoint.

For migration, the same service can optionally speak the old plaintext updater protocol through private host port `14040`; the dedicated nginx stream proxy publishes it as `4040`. Disable it after distributing the HTTPS-capable launcher. See [the launcher protocol](docs/launcher-update-protocol.md), [the HTTPS nginx example](deploy/nginx/fonline-nd.conf), and [the legacy TCP proxy](deploy/nginx/legacy-updater-stream.conf).

Central administrators can download and restore a portable wiki backup from the `Backup` tab. The archive includes the complete wiki history, completed assets and editor accounts. Restore is intentionally accepted only by an otherwise empty wiki/editor database. Deployment limits and reverse-proxy requirements are documented in [Wiki backup and restore](docs/wiki-backup.md).

## Security

- passwords are hashed with Argon2id;
- the JWT access token lasts 10 minutes by default, uses the `typ=at+jwt` header and a fixed HS256 algorithm, and validates `iss`, `aud`, `exp`, `iat`, and `jti`;
- the access token is stored only in browser memory;
- the random refresh token is held in an `HttpOnly` cookie and stored in the database only as SHA-256;
- each rotation consumes the previous refresh token, and replay detection revokes the entire session family;
- refresh and logout require an additional double-submit CSRF token;
- login attempts are rate-limited in PostgreSQL by IP address and IP/user pair;
- CORS uses an explicit origin allowlist;
- uploads go directly to S3 through short-lived signed URLs and require a SHA-256 checksum;
- all editorial operations are recorded in `audit_log`.

The login form sends the password only inside the HTTPS request body. Seeing the value in the browser's own developer tools is expected; TLS encrypts the request on the network. The frontend blocks authentication, JWT-bearing requests, and password-management requests over remote plain HTTP. In the `prod` profile, the backend also rejects every insecure `/api/**` request with HTTP `426`. Plain HTTP remains available only on loopback addresses for local development.

The browser does not replace the password with a reusable client-side hash. Such a hash would become a password-equivalent bearer secret and could be replayed after interception. Password verification remains server-side using Argon2id with a unique salt.

The central administrator can reset an editor password from **Editors → Reset password**. Resetting a password revokes that editor's existing refresh sessions. Usernames are case-insensitive and surrounding spaces are ignored; passwords remain case-sensitive and preserve all entered characters.

In the `prod` profile, the application refuses to start with default secrets, insecure cookies, or non-HTTPS public URLs.

## Primary endpoints

- `POST /api/v1/auth/login|refresh|logout`
- `GET /api/v1/wiki/pages?q=...`
- `GET /api/v1/wiki/pages/{slug}`
- `GET /api/v1/releases`
- `GET /api/v1/releases/{id}/download`
- `GET /api/v1/updates/manifest?channel=STABLE`
- `GET /api/v1/updates/files/{id}/download`
- `/api/v1/admin/wiki/**` — admin or editor
- `/api/v1/admin/users/**` — admin only
- `/api/v1/admin/releases/**` — admin only
- `/api/v1/admin/updates/**` — admin only

## Project checks

```powershell
npm run lint
npm run build
npm audit --omit=dev
Set-Location server
mvn test
```

## Production checklist

- use `compose.prod.yaml` and `.env.production`, not the development defaults;
- run the backend behind a TLS reverse proxy with the `prod` profile enabled;
- expose the API port only through a trusted reverse proxy that overwrites `Forwarded` and `X-Forwarded-For` headers;
- configure unique secrets, `COOKIE_SECURE=true`, and exact `ALLOWED_ORIGINS`;
- use a private S3 bucket, restricted IAM permissions, and a lifecycle rule for incomplete uploads;
- store secrets in a secret manager instead of an `.env` file;
- back up PostgreSQL and configure bucket versioning and retention;
- monitor local storage usage and periodically test restoration from both the
  PostgreSQL and MinIO backups;
- scan client packages for malware and sign files or platform installers;
- serve the frontend with an `index.html` fallback for `/wiki/*` and `/admin/*`.
