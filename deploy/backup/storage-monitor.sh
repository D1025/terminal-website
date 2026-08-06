#!/usr/bin/env sh
set -eu

threshold="${DISK_ALERT_PERCENT:-80}"
interval="${DISK_CHECK_INTERVAL_SECONDS:-300}"
webhook="${DISK_ALERT_WEBHOOK_URL:-}"
alert_state=/tmp/storage-alert-active
healthy_state=/tmp/storage-ok

case "${threshold}" in
    ''|*[!0-9]*) echo "DISK_ALERT_PERCENT must be an integer." >&2; exit 1 ;;
esac
case "${interval}" in
    ''|*[!0-9]*) echo "DISK_CHECK_INTERVAL_SECONDS must be an integer." >&2; exit 1 ;;
esac
if [ "${threshold}" -lt 1 ] || [ "${threshold}" -gt 99 ]; then
    echo "DISK_ALERT_PERCENT must be between 1 and 99." >&2
    exit 1
fi
if [ "${interval}" -lt 10 ]; then
    echo "DISK_CHECK_INTERVAL_SECONDS must be at least 10." >&2
    exit 1
fi

log() {
    printf '%s %s\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" "$*"
}

send_webhook() {
    message="$1"
    if [ -n "${webhook}" ]; then
        escaped="$(printf '%s' "${message}" | sed 's/\\/\\\\/g; s/"/\\"/g')"
        curl --fail --silent --show-error \
            -H 'Content-Type: application/json' \
            --data "{\"content\":\"${escaped}\"}" \
            "${webhook}" >/dev/null || log "Could not deliver the storage alert webhook."
    fi
}

while true; do
    highest=0
    highest_path=""

    for path in /volumes/postgres /volumes/minio /backups; do
        used="$(df -P "${path}" | awk 'NR == 2 { gsub(/%/, "", $5); print $5 }')"
        if [ "${used}" -gt "${highest}" ]; then
            highest="${used}"
            highest_path="${path}"
        fi
    done

    if [ "${highest}" -ge "${threshold}" ]; then
        rm -f "${healthy_state}"
        if [ ! -f "${alert_state}" ]; then
            message="NEW DAWN storage alert on ${HOSTNAME}: ${highest_path} is ${highest}% full (threshold ${threshold}%)."
            log "${message}"
            send_webhook "${message}"
            touch "${alert_state}"
        fi
    else
        touch "${healthy_state}"
        if [ -f "${alert_state}" ]; then
            message="NEW DAWN storage recovered on ${HOSTNAME}: usage is now ${highest}%."
            log "${message}"
            send_webhook "${message}"
            rm -f "${alert_state}"
        fi
    fi

    sleep "${interval}"
done
