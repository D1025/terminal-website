# Production deployment on DigitalOcean

This deployment runs the React frontend, Spring Boot API, PostgreSQL, MinIO,
automatic HTTPS, local infrastructure backups, and storage monitoring on one
Droplet. PostgreSQL, MinIO, and Spring Boot are reachable only through Docker
networks. Only the edge proxy publishes ports `80` and `443`; the optional
legacy updater proxy publishes `4040` when its Compose profile is enabled.

Local backups protect against application mistakes and accidental data
deletion. They do not protect against loss of the entire Droplet. Off-Droplet
backup replication and DigitalOcean Droplet backups are intentionally deferred.

## 1. Create the Droplet and DNS records

Use an Ubuntu LTS Droplet with at least 2 vCPU, 4 GB RAM, and 80 GB disk. Assign
it a Reserved IP. Install Docker Engine with the Compose plugin and clone the
repository, for example to `/opt/new-dawn`.

Create these Cloudflare records, initially as **DNS only** so Caddy can obtain
the first certificates:

| Type | Name | Target |
| --- | --- | --- |
| A | `@` | Droplet Reserved IP |
| CNAME | `www` | `fonline-nd.com` |
| A | `updater` | Droplet Reserved IP |
| A | `storage` | Droplet Reserved IP |
| A | `legacy-updater` | Droplet Reserved IP, only during migration |

After HTTPS works, enable the Cloudflare proxy for `@`, `www`, and `updater`.
Keep `storage` as DNS only because update and client uploads can exceed
Cloudflare's request size limit. Keep `legacy-updater` as DNS only because it
uses a custom TCP protocol. Set Cloudflare SSL/TLS mode to **Full (strict)**.

Allow inbound TCP `80` and `443` and UDP `443`. Restrict SSH to the
administrator's IP where possible. Do not open `5432`, `8080`, `9000`, or
`9001`. Open TCP `4040` only while the legacy updater is enabled.

## 2. Create production secrets

Copy the template without committing the resulting file:

```bash
cd /opt/new-dawn
cp .env.production.example .env.production
chmod 600 .env.production
sudo install -d -m 700 /var/lib/new-dawn/backups
```

Generate independent values. These commands are examples; save the values in
`.env.production`:

```bash
openssl rand -hex 32       # DATABASE_PASSWORD
openssl rand -base64 64    # JWT_SECRET
openssl rand -hex 16       # MINIO_ROOT_USER
openssl rand -base64 48    # MINIO_ROOT_PASSWORD
openssl rand -hex 16       # S3_ACCESS_KEY
openssl rand -base64 48    # S3_SECRET_KEY
openssl rand -base64 32    # BOOTSTRAP_ADMIN_PASSWORD
```

Do not reuse any generated value. MinIO's root credentials are used only by
bucket initialization and infrastructure backup. Spring receives a separate
service account restricted to the configured private bucket. Set `ACME_EMAIL`
to a working mailbox. The backend's production validator refuses default or
weak values and refuses insecure public URLs.

## 3. Start the application

Validate substitutions before starting containers:

```bash
docker compose --env-file .env.production -f compose.prod.yaml config --quiet
docker compose --env-file .env.production -f compose.prod.yaml up -d --build
docker compose --env-file .env.production -f compose.prod.yaml ps
```

Caddy automatically obtains and renews certificates. The first issuance can
take a short time after DNS changes. Inspect startup without printing the env
file:

```bash
docker compose --env-file .env.production -f compose.prod.yaml logs -f edge server
```

Verify:

```bash
curl -fsSI https://fonline-nd.com/
curl -sS -o /dev/null -w '%{http_code}\n' \
  'https://updater.fonline-nd.com/api/v1/updates/manifest?channel=STABLE'
```

The MinIO console is intentionally not published. Normal administration uses
the website. If emergency console access is ever required, add a temporary
loopback-only Compose override for `127.0.0.1:9001:9001`, connect through an SSH
tunnel, and remove the override immediately afterwards.

## 4. Local backups

The `backup` container creates a backup immediately after startup and then once
per `BACKUP_INTERVAL_SECONDS` (24 hours by default):

- `postgres/<UTC timestamp>.dump` is a PostgreSQL custom-format dump;
- `postgres/<UTC timestamp>.dump.sha256` verifies that dump;
- `minio/<UTC timestamp>/` is a complete mirror of the private bucket;
- `.last-success` drives the backup health check.

Completed copies are kept for `BACKUP_RETENTION_DAYS` (7 days by default).
Partial copies never replace a completed copy and are cleaned up separately.
Check status and recent files:

```bash
docker compose --env-file .env.production -f compose.prod.yaml ps backup
docker compose --env-file .env.production -f compose.prod.yaml logs --tail=100 backup
sudo find /var/lib/new-dawn/backups -maxdepth 2 -type f -printf '%TY-%Tm-%Td %TH:%TM %p\n' | sort
```

Periodically perform a restore test before treating a backup as reliable.

### PostgreSQL restore

Stop writers, select an exact dump, and verify its checksum first:

```bash
docker compose --env-file .env.production -f compose.prod.yaml stop edge server backup
cd /var/lib/new-dawn/backups/postgres
sha256sum -c 20260806T120000Z.dump.sha256
cd /opt/new-dawn
docker compose --env-file .env.production -f compose.prod.yaml run --rm --no-deps \
  -e RESTORE_CONFIRM=restore-postgres-20260806T120000Z \
  --entrypoint /usr/local/bin/restore.sh \
  backup postgres 20260806T120000Z
docker compose --env-file .env.production -f compose.prod.yaml up -d
```

Restoring cleans the current database objects before recreating them from the
dump. Run it only during a planned maintenance window and take an additional
copy first.

### MinIO restore

Each timestamp directory can be mirrored back with the `mc` client contained
in the maintenance image. Stop `server` and `backup`, confirm the selected
timestamp, and restore into the private bucket. The `--remove` option makes the
bucket match that snapshot exactly and therefore deletes newer objects; use it
only when a full rollback is intended.

```bash
docker compose --env-file .env.production -f compose.prod.yaml stop edge server backup
docker compose --env-file .env.production -f compose.prod.yaml run --rm --no-deps \
  -e RESTORE_CONFIRM=restore-minio-20260806T120000Z \
  --entrypoint /usr/local/bin/restore.sh \
  backup minio 20260806T120000Z
docker compose --env-file .env.production -f compose.prod.yaml up -d
```

## 5. Disk monitoring

`storage-monitor` checks the filesystem containing PostgreSQL, MinIO, and local
backups every five minutes. At `DISK_ALERT_PERCENT` usage (80% by default), it:

- writes a clear alert to Docker logs;
- changes its Docker health status to `unhealthy`;
- sends a Discord-compatible webhook message when
  `DISK_ALERT_WEBHOOK_URL` is configured.

Without a webhook, health and logs still expose the problem but no external
notification is sent:

```bash
docker compose --env-file .env.production -f compose.prod.yaml ps storage-monitor
docker compose --env-file .env.production -f compose.prod.yaml logs storage-monitor
```

## 6. Legacy updater migration

The legacy listener is off by default. To enable it, set
`LEGACY_UPDATER_ENABLED=true`, open TCP `4040`, and start the `legacy` profile:

```bash
docker compose --env-file .env.production -f compose.prod.yaml \
  --profile legacy up -d
```

Old launchers then use `legacy-updater.fonline-nd.com:4040`. Once all launchers
use HTTPS, set the flag back to `false`, stop the profile, and close port `4040`.

## 7. Updating the website

```bash
cd /opt/new-dawn
git pull --ff-only
docker compose --env-file .env.production -f compose.prod.yaml up -d --build
docker compose --env-file .env.production -f compose.prod.yaml ps
```

Do not run `docker compose down -v` in production. The `-v` flag removes the
PostgreSQL, MinIO, and certificate volumes.
