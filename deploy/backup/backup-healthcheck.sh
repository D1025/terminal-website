#!/usr/bin/env sh
set -eu

status_file=/backups/.last-success
max_age="${BACKUP_MAX_AGE_SECONDS:-129600}"

case "${max_age}" in
    ''|*[!0-9]*) exit 1 ;;
esac

test -f "${status_file}"
last_success="$(stat -c %Y "${status_file}")"
now="$(date +%s)"
age="$((now - last_success))"
test "${age}" -le "${max_age}"
