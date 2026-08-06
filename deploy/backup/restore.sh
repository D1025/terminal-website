#!/usr/bin/env bash
set -euo pipefail

umask 077

usage() {
    echo "Usage: restore.sh <postgres|minio> <UTC timestamp>" >&2
    exit 2
}

[[ "$#" -eq 2 ]] || usage
readonly component="$1"
readonly timestamp="$2"
readonly expected_confirmation="restore-${component}-${timestamp}"

if [[ ! "${timestamp}" =~ ^[0-9]{8}T[0-9]{6}Z$ ]]; then
    echo "Invalid backup timestamp: ${timestamp}" >&2
    exit 2
fi

if [[ "${RESTORE_CONFIRM:-}" != "${expected_confirmation}" ]]; then
    echo "Restore refused. Set RESTORE_CONFIRM=${expected_confirmation} after stopping application writers." >&2
    exit 3
fi

case "${component}" in
    postgres)
        dump="/backups/postgres/${timestamp}.dump"
        checksum="${dump}.sha256"
        [[ -f "${dump}" && -f "${checksum}" ]] || {
            echo "PostgreSQL backup ${timestamp} is incomplete or missing." >&2
            exit 4
        }
        (cd /backups/postgres && sha256sum --check "${timestamp}.dump.sha256")
        pg_isready -q
        pg_restore --clean --if-exists --no-owner --no-acl \
            --dbname="${PGDATABASE}" "${dump}"
        ;;
    minio)
        source_directory="/backups/minio/${timestamp}"
        [[ -f "${source_directory}/.complete" ]] || {
            echo "MinIO backup ${timestamp} is incomplete or missing." >&2
            exit 4
        }
        mc alias set target "${MINIO_ENDPOINT}" "${MINIO_ACCESS_KEY}" "${MINIO_SECRET_KEY}" >/dev/null
        mc mirror --preserve --overwrite --remove --exclude '.complete' \
            "${source_directory}" "target/${MINIO_BUCKET}"
        ;;
    *)
        usage
        ;;
esac

echo "Restored ${component} from ${timestamp}."
