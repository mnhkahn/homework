# Trello Homework Service Design

## Decision

`cyeam_web` is the trusted bridge between the Android tablet and Trello.

```text
Android tablet  <--- device token --->  cyeam.com  <--- Trello user token --->  Trello
```

The tablet never receives a Trello token. `cyeam.com` owns the Trello authorization
callback and encrypts the resulting token before it is persisted.

## Parent pairing flow

The child-facing tablet does not need a parent account or password.

1. The app creates a random pairing request and opens its `authorize_url` in a browser.
2. The parent signs into Trello and grants read/write permission to **作业小伙伴**.
3. Trello redirects to `https://www.cyeam.com/homework/trello/callback`.
4. The callback page reads the URL fragment containing the Trello token and sends it
   over HTTPS to the service. The service verifies its signed `state`, encrypts and stores
   the token, then lets the parent select a board, a `今日作业` list, and an `已完成` list.
5. The tablet polls its pairing request. Once complete, it receives a long-lived opaque
   device token, stores it with Android Keystore-backed storage, and immediately syncs tasks.

The Trello Power-Up's **Allowed Origin** is `https://www.cyeam.com` and its callback URL
is `https://www.cyeam.com/homework/trello/callback`. The API key and the encryption key are
environment variables on `cyeam_web`; neither is bundled in the Android app.

## Trello board convention

One family uses one board.

- `今日作业` list: each open card is a task.
- `已完成` list: the service moves a card here only after a successful photo submission.
- Card title: homework content.
- Card due date: task deadline; cards whose due date is today are returned to the tablet.
- First Trello label: subject, such as `数学` or `英语`.
- Description line `预计用时: 20`: estimated duration in minutes. Missing or invalid values
  default to 20 minutes.

This keeps parent-side entry simple and avoids requiring Trello custom fields for v1.

## HTTP API

All `/api/homework/*` routes return JSON and use an opaque bearer device token after pairing.

| Route | Purpose |
|---|---|
| `POST /api/homework/pairings` | Create a pending pairing and return browser authorization URL. |
| `GET /api/homework/pairings/{id}` | Tablet polls status using the one-time pairing secret. |
| `GET /homework/trello/callback` | Browser callback page; JavaScript receives Trello token fragment. |
| `POST /api/homework/trello/callback` | Callback page submits token and signed state to the server. |
| `GET /homework/trello/boards` | Parent page lists boards after authorization. |
| `POST /homework/trello/configure` | Parent selects board and source/done lists. |
| `GET /api/homework/tasks?date=YYYY-MM-DD` | Tablet fetches today's normalized cards. |
| `POST /api/homework/tasks/{card_id}/submit` | Tablet sends multipart photo and submission metadata. |
| `DELETE /api/homework/connection` | Revoke and remove the current family's connection. |

`submit` uploads the photo as a Trello card attachment first. Only after that succeeds does it
move the card to `已完成` and add a `已提交` comment with local submission time and overtime status.
The API is idempotent using a client submission ID, so retrying after a network loss cannot add
duplicate attachments or comments.

## Data model

PostgreSQL tables, all keyed by random IDs rather than Trello identifiers:

- `homework_connections`: encrypted Trello token, selected board/list IDs, encryption key version,
  creation and revocation timestamps.
- `homework_devices`: connection ID, SHA-256 hash of device token, display name, last-seen time,
  revoked time.
- `homework_pairings`: short-lived state hash, polling secret hash, connection ID, expiry,
  consumed time.
- `homework_submissions`: device ID, Trello card ID, client submission ID, submitted time,
  overtime flag, attachment ID; unique on `(device_id, client_submission_id)`.

The token is encrypted with AES-256-GCM using `HOMEWORK_TOKEN_ENCRYPTION_KEY` before database
storage. The encryption key and `TRELLO_API_KEY` are deployment secrets, never committed to Git.

## Android responsibilities

- Store only the cyeam device token, with Android Keystore-backed encrypted storage.
- Cache the last successful task response in Room for offline timing and reminders.
- Start the local timer, determine overtime locally, invoke CameraX, and queue unsent submissions.
- Treat a `401` from cyeam.com as a revoked connection and restart pairing.

## Non-goals for v1

- No Trello webhook is required: the tablet refreshes on foreground/startup and at a modest interval
  while the homework screen is open.
- No multi-family dashboard or parent login in cyeam.com.
- No data is written to Trello during timer start/pause; only final submission updates a card.

## Deployment configuration

```text
TRELLO_API_KEY=<Power-Up API key>
HOMEWORK_TOKEN_ENCRYPTION_KEY=<32-byte random base64 value>
HOMEWORK_PUBLIC_URL=https://www.cyeam.com
```

Before deployment, add `https://www.cyeam.com` under the Power-Up API key's Allowed Origins.

## As-built deviations (Android)

The shipped tablet app talks to Trello directly instead of through the cyeam.com bridge:

- The Trello user token is stored on the tablet, encrypted with Android Keystore-backed
  AES/GCM (`DeviceTokenCipher`), in SharedPreferences `trello_connection`. The parent
  authorizes via `https://trello.com/1/authorize` and the app-link callback
  `https://www.cyeam.com/homework/trello/android/callback` delivers the token fragment
  back to `MainActivity`.
- Any Trello API answer with HTTP 401 means the token expired or was revoked
  (`AuthorizationExpiredException`). The app then clears the stored connection and
  reopens the authorization dialog at the "授权 Trello" step; after consent the parent
  re-selects the board. If the device is in study mode, authorizing first pauses the
  lockdown for 15 minutes so the browser can open.
- Failed submissions stay in `PendingSubmissionStore` and are retried by the 60-second
  refresh loop.

## Study-time app blocking (Android)

Study time is defined by `KioskPolicy` (SharedPreferences `kiosk_policy`, default
17:00–21:30). Two layers keep non-allowlisted apps closed during study time:

1. Device Owner + Lock Task: `setLockTaskPackages()` allowlists this app plus
   `study_packages` and temporary camera entries; `ChildLauncherActivity` becomes the
   persistent preferred HOME.
2. UsageStats fallback (`StudySessionService` 1-second loop →
   `KioskPolicy.blockedForegroundPackage()`): if a launchable app outside the allowlist
   reaches the foreground anyway (e.g. the HyperOS tablet window menu closing a locked
   task), `StudyBlockActivity` is launched over it via a BAL-enabled PendingIntent and
   offers a "返回作业" button. This layer requires the `PACKAGE_USAGE_STATS` app-op,
   granted from `Settings.ACTION_USAGE_ACCESS_SETTINGS` or
   `adb shell appops set com.homeworkbuddy PACKAGE_USAGE_STATS allow`; the parent
   settings screen links there while the permission is missing.
