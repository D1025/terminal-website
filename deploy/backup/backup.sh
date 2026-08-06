#!/usr/bin/env bash
set -uo pipefail

umask 077

readonly POSTGRES_BACKUP_ROOT=/backups/postgres
readonly MINIO_BACKUP_ROOT=/backups/minio
readonly STATUS_FILE=/backups/.last-success

log() {
    printf '%s %s\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" "$*"
}

wait_for_dependencies() {
    until pg_isready -q; do
        log "Waiting for PostgreSQL."
        sleep 5
    done

    until mc alias set source "${MINIO_ENDPOINT}" "${MINIO_ACCESS_KEY}" "${MINIO_SECRET_KEY}" >/dev/null 2>&1 \
        && mc ready source >/dev/null 2>&1; do
        log "Waiting for MinIO."
        sleep 5
    done
}

create_backup() {
    local timestamp="$1"
    local database_partial="${POSTGRES_BACKUP_ROOT}/.${timestamp}.dump.partial"
    local database_target="${POSTGRES_BACKUP_ROOT}/${timestamp}.dump"
    local minio_partial="${MINIO_BACKUP_ROOT}/.${timestamp}.partial"
    local minio_target="${MINIO_BACKUP_ROOT}/${timestamp}"

    rm -f -- "${database_partial}"
    rm -rf -- "${minio_partial}"
    mkdir -p -- "${minio_partial}"

    log "Creating PostgreSQL backup."
    pg_dump --format=custom --no-owner --no-acl --file="${database_partial}"

    log "Creating MinIO backup."
    mc mirror --preserve --overwrite "source/${MINIO_BUCKET}" "${minio_partial}"
    touch "${minio_partial}/.complete"

    mv -- "${database_partial}" "${database_target}"
    sha256sum "${database_target}" > "${database_target}.sha256"
    mv -- "${minio_partial}" "${minio_target}"

    printf '%s\n' "${timestamp}" > "${STATUS_FILE}"
    touch "${STATUS_FILE}"
    log "Backup completed: ${timestamp}."
}

prune_backups() {
    log "Removing backups older than ${BACKUP_RETENTION_DAYS} days."
    find "${POSTGRES_BACKUP_ROOT}" -maxdepth 1 -type f \
        \( -name '*.dump' -o -name '*.dump.sha256' \) \
        -mtime "+${BACKUP_RETENTION_DAYS}" -delete
    find "${MINIO_BACKUP_ROOT}" -mindepth 1 -maxdepth 1 -type d \
        ! -name '.*.partial' -mtime "+${BACKUP_RETENTION_DAYS}" \
        -exec rm -rf -- {} +
    find "${MINIO_BACKUP_ROOT}" -mindepth 1 -maxdepth 1 -type d \
        -name '.*.partial' -mtime +1 -exec rm -rf -- {} +
    find "${POSTGRES_BACKUP_ROOT}" -maxdepth 1 -type f \
        -name '.*.partial' -mtime +1 -delete
}

required_variables=(
    PGHOST PGDATABASE PGUSER PGPASSWORD
    MINIO_ENDPOINT MINIO_ACCESS_KEY MINIO_SECRET_KEY MINIO_BUCKET
)
for variable in "${required_variables[@]}"; do
    if [[ -z "${!variable:-}" ]]; then
        log "Required variable ${variable} is missing."
        exit 1
    fi
done

BACKUP_INTERVAL_SECONDS="${BACKUP_INTERVAL_SECONDS:-86400}"
BACKUP_RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-7}"
if [[ ! "${BACKUP_INTERVAL_SECONDS}" =~ ^[0-9]+$ ]] || ((BACKUP_INTERVAL_SECONDS < 300)); then
    log "BACKUP_INTERVAL_SECONDS must be an integer of at least 300."
    exit 1
fi
if [[ ! "${BACKUP_RETENTION_DAYS}" =~ ^[0-9]+$ ]] || ((BACKUP_RETENTION_DAYS < 1)); then
    log "BACKUP_RETENTION_DAYS must be a positive integer."
    exit 1
fi
mkdir -p -- "${POSTGRES_BACKUP_ROOT}" "${MINIO_BACKUP_ROOT}"
wait_for_dependencies

while true; do
    timestamp="$(date -u +'%Y%m%dT%H%M%SZ')"
    if (set -e; create_backup "${timestamp}"; prune_backups); then
        :
    else
        log "Backup failed. The previous completed backups were left untouched."
    fi
    sleep "${BACKUP_INTERVAL_SECONDS}"
done
