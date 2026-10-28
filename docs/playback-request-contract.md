# Playback request and cloud-command contract

This is an implementation contract for AboEzzat Receiver. It documents target behavior; it does not claim that header forwarding or Firebase cloud control is already implemented.

## Local playback request

The sender and Receiver should converge on one validated request model:

- `requestId`: unique ID for acknowledgement/deduplication
- `action`: allow-listed operation such as `play`, `pause`, `stop`, `seek`, or `setVolume`
- `url`: absolute HTTP(S) URL only for `play`
- `headers`: optional map limited to approved media request headers
- `createdAt` / `expiresAt`: bounded command lifetime
- `source`: `lan` or `cloud`, for diagnostics only (never an authorization decision)

For compatibility, preserve the existing local HTTP 8081 contract until a versioned migration is implemented. Do not expose that listener to the public internet.

## Header handling

- Allow only a small header allow-list such as User-Agent, Referer and Origin when a source demonstrably needs them.
- Never accept arbitrary Host, Connection, Content-Length, proxy, or hop-by-hop headers from the sender.
- Do not forward Cookie or Authorization values by default. If a future feature supports them, require explicit user intent, TLS in transit, strict ownership checks, short retention and log redaction.
- Enforce URL/header count and byte-size limits; reject CR/LF in header names or values.
- Apply headers through Media3's HTTP data-source configuration for the actual media request. Do not claim that this defeats DRM, site login, token expiry or server-side authorization.
- Avoid leaking sensitive query parameters and headers into history, diagnostics, crash logs or acknowledgements.

## Media3 validation

Test MP4, HLS (.m3u8), DASH (.mpd), redirects, expired links, invalid MIME types and missing/denied Referer cases. Separate audio/video tracks need compatible formats and timelines; only combine them when the stream metadata supports it. Report actionable playback errors without logging secrets.

## Cloud command lifecycle (future integration)

A cloud command must be authenticated and scoped to the signed-in owner's device. Receiver should reject unknown, expired, replayed or malformed commands, acknowledge accepted/rejected/completed outcomes, and keep local playback working during cloud outages. Device IDs are identifiers, not credentials. Use server timestamps for heartbeat freshness and bounded retention for history.

## Release gates

- Unit tests for URL, command and header validation.
- Firebase Emulator tests for unauthenticated denial and two-account isolation.
- CI Android build and test run.
- Device tests on Android TV/Fire TV, including background state and reboot/network-loss recovery.
- Do not call cloud control complete until project configuration and end-to-end device tests pass.
