# HTTPS launcher update protocol

## Network layout

The launcher is configured with one base address:

```text
https://updater.fonline-nd.com/api/v1
```

It does not connect to port `8080`. Public TLS terminates at the reverse proxy on port `443`, and the proxy forwards only the public update routes to the private Spring Boot service. The game connection remains separate, for example `server.fonline-nd.com:2238`.

The old launcher cannot use this endpoint unchanged. Its `TTcpClient` implements the custom plaintext `Hello2` protocol and has no TLS or HTTP support. The replacement launcher should use Windows WinHTTP/SChannel (or another maintained HTTPS implementation), validate the certificate normally, and follow HTTP redirects.

## Manifest

Request the active release:

```http
GET /api/v1/updates/manifest?channel=STABLE HTTP/1.1
Host: updater.fonline-nd.com
Accept: application/json
```

The response is a complete desired-state snapshot:

```json
{
  "schemaVersion": 1,
  "releaseId": "3f236a8b-38dd-4b65-9ef2-51bf8bd05fbb",
  "version": "0.2.2",
  "channel": "STABLE",
  "minimumLauncherVersion": "1.0.0",
  "gameServerHost": "server.fonline-nd.com",
  "gameServerPort": 2238,
  "publishedAt": "2026-08-06T18:30:00Z",
  "manifestSha256": "...",
  "files": [
    {
      "id": "a1382697-29ea-496f-8494-340b59a16a5c",
      "path": "data/patch010.zip",
      "action": "UPSERT",
      "overwritePolicy": "REPLACE",
      "sizeBytes": 233121,
      "sha256": "...",
      "legacyCrc32": 123456789,
      "downloadUrl": "/api/v1/updates/files/a1382697-29ea-496f-8494-340b59a16a5c/download"
    },
    {
      "id": "f7ed0cf1-2b47-42ef-80c5-17cf34613b1e",
      "path": "data/obsolete.zip",
      "action": "DELETE",
      "overwritePolicy": "REPLACE",
      "sizeBytes": 0
    }
  ]
}
```

`downloadUrl` is relative to the same API base. It returns `302` to a short-lived HTTPS URL on `storage.fonline-nd.com`; the launcher must follow the redirect. The backend never needs a public `8080` port and does not proxy large update bodies.

## Required launcher algorithm

1. Fetch the manifest over HTTPS and reject unsupported `schemaVersion` values.
2. Normalize every manifest path as a relative path below the selected game directory. Reject absolute paths, drive letters and `..` segments even though the API validates them too.
3. For `PRESERVE`, skip an existing local file. If it is missing, treat it as a normal download.
4. Hash local files with SHA-256. Download only missing or mismatched `UPSERT` entries.
5. Download every required object to a release-specific staging directory. Support resume with HTTP `Range` where the HTTP implementation permits it.
6. Verify the staged file size and SHA-256 before touching the installation.
7. Ensure the game is not running. Apply replacements using same-volume atomic renames and keep a rollback journal.
8. Process `DELETE` entries only after every replacement has been verified and staged.
9. Replace the launcher itself through a small helper process or the existing `SWAP` mechanism.
10. Persist the applied release ID only after the full transaction succeeds. On failure, restore the rollback journal.

The `legacyCrc32` field matches the old launcher and exists only to aid migration/diagnostics. SHA-256 is authoritative for the HTTPS updater.

## Migration from the TCP updater

1. Deploy the web/API module and publish the first HTTPS manifest.
2. Build a launcher version that implements this document.
3. Put that launcher executable into the existing `Server/updater` snapshot and distribute it once through `UpdaterServer.exe`; the current launcher already knows how to replace itself using `SWAP`.
4. After the supported client population has migrated, close the old updater TCP port and remove `UpdaterServer.exe`.

### Built-in compatibility gateway

The API service includes an optional compatibility gateway for the existing desktop updater. It serves the currently published release from the same database and object storage, so a separate `UpdaterServer.exe` and a second update directory are not required.

Enable it only for the migration window:

```dotenv
LEGACY_UPDATER_ENABLED=true
LEGACY_UPDATER_PORT=4040
LEGACY_UPDATER_CHANNEL=STABLE
```

Inside the container the gateway listens on `4040`, while compose maps it to private host address `127.0.0.1:14040`. The separate host port avoids a bind conflict with nginx. To accept old clients, load [`deploy/nginx/legacy-updater-stream.conf`](../deploy/nginx/legacy-updater-stream.conf) at nginx's top level and allow public TCP port `4040` in the firewall. Configure the current launcher with:

```text
updater.fonline-nd.com
4040
```

This connection remains plaintext because the old launcher does not implement TLS. A DNS name does not add HTTPS by itself, and an HTTP reverse proxy cannot translate this custom TCP protocol into HTTPS. The stream proxy only keeps the application listener private and gives one controlled public entry point.

The gateway intentionally exposes only ready `UPSERT` files smaller than 2 GiB. It maps `PRESERVE` to the old `<norewrite>` flag; deletion entries are applied only by the new HTTPS launcher. Once clients receive the HTTPS-capable launcher, set `LEGACY_UPDATER_ENABLED=false`, remove the stream block and close public port `4040`.
