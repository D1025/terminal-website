FROM quay.io/minio/mc:latest AS minio-client

FROM postgres:17-alpine
RUN apk add --no-cache bash coreutils curl tzdata
COPY --from=minio-client /usr/bin/mc /usr/local/bin/mc
COPY deploy/backup/backup.sh /usr/local/bin/backup.sh
COPY deploy/backup/backup-healthcheck.sh /usr/local/bin/backup-healthcheck.sh
COPY deploy/backup/restore.sh /usr/local/bin/restore.sh
COPY deploy/backup/storage-monitor.sh /usr/local/bin/storage-monitor.sh
RUN chmod 0555 /usr/local/bin/backup.sh \
    /usr/local/bin/backup-healthcheck.sh \
    /usr/local/bin/restore.sh \
    /usr/local/bin/storage-monitor.sh
ENTRYPOINT ["/usr/local/bin/backup.sh"]
