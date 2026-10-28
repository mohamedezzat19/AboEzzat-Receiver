# Cloud control integration contract (foundation)

## Current behavior and security boundary

Receiver currently exposes its local control listener on TCP port 8081 and advertises itself through local-network multicast. This is intended for a trusted LAN. **Do not port-forward 8081 or expose it directly to the public internet.** The existing foreground service and playback UI must remain compatible.

## Cloud-managed device model

Each installed Receiver should eventually have a random installation-scoped device ID and be registered under the signed-in owner's account:

- `deviceId`: random, non-secret identifier
- `name`: user-editable TV/device label
- `appVersion`: installed app version
- `lastSeen`: server timestamp
- `status`: online/stale, derived from heartbeat age rather than trusted as a client claim
- `capabilities`: supported playback/control operations

Never use the device ID as an authentication credential. Store no Firebase service-account keys or admin secrets in the Android app.

## Command envelope

A future cloud command should contain `id`, `deviceId`, `type`, `payload`, `createdAt`, `expiresAt`, and `status`. Receiver must:
1. accept only a strict allow-list of command types;
2. reject expired commands and duplicate IDs;
3. validate URLs and payload sizes before dispatch;
4. acknowledge accepted/rejected/completed state;
5. avoid logging full private URLs or tokens;
6. keep local playback behavior working when cloud access is unavailable.

## Implementation sequence

1. Firebase Authentication and per-owner database rules.
2. Receiver registration + heartbeat, with online/stale display in admin.
3. Expiring command queue and acknowledgements for remote playback controls.
4. Sanitized diagnostics and history with bounded retention.
5. Version/update metadata and user-confirmed Android update flow.
6. Emulator security tests, two-account isolation tests, and device testing on Android TV/Fire TV.

Cloud control is not active merely by adding this contract. It becomes operational only after project-specific Firebase configuration and app integration are supplied and verified.
