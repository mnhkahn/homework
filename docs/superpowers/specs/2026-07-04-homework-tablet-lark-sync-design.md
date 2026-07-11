# Homework Tablet Lark Sync Design

## Goal

Build an Android tablet homework execution app backed by Feishu/Lark Base. Parents create or update homework remotely in a Base table. The tablet receives change notifications, syncs the latest assignments, handles local timing and reminders from 17:00 to 21:00, and writes completion results plus homework photos back to the same Base row.

## Product Scope

The first version is for one family and one Android tablet.

In scope:

- Parent remotely creates and edits homework in Feishu Base.
- One Base row represents one homework task.
- The tablet syncs today's homework from Base through the service.
- The tablet handles local task status, timer, deadline reminders, and overtime warnings.
- The child submits homework by taking a photo on the tablet.
- The service writes submission status, submission time, overtime result, and photo attachment back to the same Base row.
- Parent checks the day's homework directly in Feishu Base.

Out of scope for the first version:

- Multi-family account system.
- Teacher or class management.
- AI homework grading.
- Complex statistics dashboards.
- High-frequency real-time status writing for start, pause, or timer heartbeat.
- Direct Android access to Feishu OpenAPI.

## High-Level Architecture

```text
Parent edits Feishu Base
        |
        v
Feishu Base record changed event
        |
        v
cyeam_web service
        |
        v
Android tablet receives homework_changed
        |
        v
Tablet pulls today's homework from cyeam_web
        |
        v
Tablet stores locally, schedules reminders, captures submission photo
        |
        v
cyeam_web uploads photo and updates the same Base row
```

Feishu Base is the business source of truth. The Android tablet keeps a local Room cache for reliability and offline execution. The service in `~/code/cyeam_web` acts as the secure bridge between Feishu and the tablet.

## Why A Service Is Required

The tablet should not call Feishu OpenAPI directly.

Reasons:

- Feishu `app_secret` must not be embedded in an Android APK.
- Feishu event delivery is designed for server-side Webhook or SDK long connection.
- Android cannot reliably expose a public Webhook endpoint.
- Android background long connections are vulnerable to power management.
- Feishu API request usage must be controlled centrally because the quota is limited to 5000 requests per month.
- The service can adapt to Base schema changes without requiring an app release.

## Feishu Base Data Model

One row represents one homework task.

Recommended fields:

| Field | Type | Purpose |
|---|---|---|
| `日期` | Date | Homework date, e.g. `2026-07-04` |
| `排序` | Number | Display order for the day |
| `科目` | Single select or text | Subject |
| `作业内容` | Text | Assignment description |
| `预计用时` | Number | Estimated minutes |
| `截止时间` | Date/time or text time | Deadline within 17:00-21:00 |
| `任务版本` | Number | Parent increments when content or deadline changes |
| `状态` | Single select | Current task status |
| `开始时间` | Date/time | Optional, only written if needed |
| `提交时间` | Date/time | Written on final submission |
| `是否超时` | Checkbox or single select | Computed by tablet/service at submission |
| `提交照片` | Attachment | Final homework photo |
| `平板备注` | Text | Optional submission note or error text |
| `最后同步时间` | Date/time | Optional diagnostic field |

Recommended status values:

```text
未开始
进行中
已提交
超时
需重做
已取消
```

The first implementation should only write final states back to Base. Local transient states such as timer ticks, pause/resume, and repeated reminder attempts remain on the tablet.

## Event Subscription

Feishu supports Base record change push events.

Relevant official capabilities:

- Subscribe cloud document events with `POST /open-apis/drive/v1/files/:file_token/subscribe`.
- Use `file_type=bitable` for Base.
- Receive `drive.file.bitable_record_changed_v1`.
- Event actions include `record_added`, `record_deleted`, and `record_edited`.
- Event payload includes `file_token`, `table_id`, `revision`, `record_id`, and changed field values.

Important limitations:

- Formula field value changes do not trigger record change events.
- Formula field values are not included in the event body.
- Events should be treated as change notifications, not as the full source payload.
- Event delivery can be repeated or delayed, so the service must deduplicate by `event_id`.

## Service Design In `cyeam_web`

The service should live inside the existing Go project at `~/code/cyeam_web`.

Responsibilities:

- Receive Feishu event callbacks.
- Verify and parse Feishu event payloads.
- Deduplicate event IDs.
- Detect homework Base changes.
- Notify registered tablet clients that homework changed.
- Expose a normalized API for the tablet to fetch today's tasks.
- Accept tablet submissions.
- Upload photos to Feishu and update the corresponding Base row.
- Enforce request-budget discipline.

Initial service endpoints:

```text
POST /api/homework/lark/events
```

Receives Feishu event verification and event callbacks.

```text
GET /api/homework/events/latest
```

Debug endpoint to inspect recently received events during development. This should be protected or disabled in production.

```text
GET /api/homework/tasks?date=2026-07-04
```

Returns normalized homework tasks for the tablet.

```text
POST /api/homework/tasks/{record_id}/submit
```

Accepts photo and completion metadata from the tablet, uploads the photo, and updates the same Base row.

Later service endpoint:

```text
GET /api/homework/ws?device_id=tablet-001
```

Keeps a tablet WebSocket connection open during active homework time and sends lightweight change events.

Push message shape:

```json
{
  "type": "homework_changed",
  "date": "2026-07-04",
  "revision": 41
}
```

## Android Tablet Design

Core technologies:

- Kotlin
- Jetpack Compose
- Room
- AlarmManager
- WorkManager
- CameraX

Tablet responsibilities:

- Authenticate to `cyeam_web` with a device token or paired device secret.
- Sync today's tasks from the service.
- Store tasks locally in Room.
- Render today's homework list.
- Enforce the 17:00-21:00 homework window.
- Schedule local deadline reminders.
- Track local start, pause, and completion progress.
- Capture final homework photo.
- Submit photo and completion metadata to the service.
- Queue submission locally if network is unavailable.

The tablet must not depend on network availability for reminders. Once tasks are synced locally, reminders and overtime detection are local.

## Homework Time Rules

Default homework window:

```text
17:00-21:00 Asia/Shanghai
```

Rules:

- Before 17:00, the tablet may show today's tasks but should not trigger overtime reminders.
- At 17:00, the tablet reminds the child that homework time has started.
- Each task has a deadline from Base.
- If the task is not submitted by its deadline, the tablet marks it locally as overtime and shows a strong reminder.
- Repeated overtime reminders are local only and must not write repeated updates to Base.
- At 21:00, the tablet shows a summary of submitted, overtime, and incomplete tasks.

## Submission Flow

```text
Child taps Submit
-> Tablet opens CameraX
-> Child takes photo
-> Tablet shows preview
-> Child confirms or retakes
-> Tablet sends photo and metadata to cyeam_web
-> cyeam_web uploads photo as Base attachment
-> cyeam_web updates the same Base row
-> Tablet marks local task as synced
```

Submission payload from tablet to service should include:

```json
{
  "record_id": "recxxxx",
  "task_version": 3,
  "submitted_at": "2026-07-04T18:12:00+08:00",
  "is_overtime": false,
  "note": "",
  "photo": "<multipart file>"
}
```

The service should reject or flag stale submissions when `task_version` is older than the current Base row version.

## Conflict Rules

When a Base row changes:

- If the task is `未开始`, the tablet updates it directly.
- If the task is `进行中`, the tablet updates content and deadline but shows that the task changed.
- If the task is `已提交`, the tablet does not erase local submission evidence.
- If the parent wants a redo, the parent changes status to `需重做`.
- If status is `已取消`, the tablet hides it from the active list but keeps it in history.

When the tablet submits:

- If Base `任务版本` matches local version, write the result normally.
- If Base `任务版本` is newer, return a conflict response and ask the tablet to sync before final submission.

## Request Budget

Quota assumption:

```text
5000 Feishu API requests per month
```

Daily average:

```text
5000 / 30 = about 166 requests per day
```

Expected usage without polling:

```text
Initial or daily sync: 1-3 requests/day
Parent edits causing sync: 1-10 requests/day
Each submitted homework:
  photo upload: 1-3 requests
  row update: 1 request
```

For 8 tasks per day:

```text
3 sync requests
5 edit-triggered sync requests
8 * 4 submission requests
= about 40 requests/day
= about 1200 requests/month
```

For 15 tasks per day:

```text
3 sync requests
10 edit-triggered sync requests
15 * 4 submission requests
= about 73 requests/day
= about 2190 requests/month
```

The 5000 request monthly limit is enough if the system avoids frequent polling.

Budget rules:

- Do not poll every few minutes.
- Do not write timer heartbeat to Feishu.
- Do not write every start/pause/resume event to Feishu.
- Do not write repeated overtime reminders to Feishu.
- Debounce parent edits for 5-10 seconds before notifying the tablet.
- Batch fetch today's tasks when possible.
- Only upload the final photo for each task unless retake history is explicitly required.
- Use low-frequency fallback sync only on app start, 16:50, and occasional recovery.

## Reliability Strategy

Events are notification signals, not durable state.

The tablet should sync in these cases:

- App starts.
- Device reconnects to the service.
- Service sends `homework_changed`.
- 16:50 daily pre-homework sync.
- Manual refresh.
- Low-frequency fallback during the homework window, at most once every 30-60 minutes.

The service should:

- Deduplicate Feishu events by `event_id`.
- Store recent event IDs with TTL.
- Debounce multiple Base changes for the same date.
- Avoid writing to Feishu unless there is a final submission or explicit status change.

The tablet should:

- Persist fetched tasks in Room.
- Persist pending submissions locally.
- Retry pending submissions with backoff.
- Continue reminders offline.

## Security

Service secrets:

- Feishu App ID and App Secret live only on the server.
- Feishu verification token and encrypt key live only on the server.
- Android uses a device token issued by `cyeam_web`.

Endpoint protection:

- Feishu event endpoint validates Feishu signatures or SDK verification.
- Tablet APIs require device authentication.
- Debug endpoints require admin login or are disabled in production.

Privacy:

- Homework photos are child-related data and should not be publicly accessible.
- Attachment access should rely on Feishu permissions.
- Local tablet storage should stay in app-private storage.

## Implementation Phases

### Phase 1: Feishu Event Intake

Goal: prove that Feishu Base changes reach `cyeam_web`.

Tasks:

- Add `POST /api/homework/lark/events`.
- Handle Feishu URL verification.
- Parse `drive.file.bitable_record_changed_v1`.
- Log `event_id`, `file_token`, `table_id`, `record_id`, `revision`, and action.
- Add a protected debug endpoint for latest received events.
- Add event deduplication.

Success criteria:

- Editing a Base row causes an event visible in service logs or debug endpoint.
- Duplicate events do not trigger duplicate downstream handling.

### Phase 2: Read Today's Homework

Goal: expose normalized homework data to the tablet.

Tasks:

- Configure Base token, table ID, and field IDs in server config.
- Implement Feishu Base record query for a date.
- Map Base fields to service JSON.
- Add `GET /api/homework/tasks?date=YYYY-MM-DD`.
- Add tests for field mapping and date filtering.

Success criteria:

- Calling the endpoint returns only the requested day's homework rows.
- Response shape is stable even if Base field names are localized.

### Phase 3: Tablet Notification Channel

Goal: notify the tablet when homework changes.

Tasks:

- Register tablet device IDs.
- Add WebSocket endpoint for active tablet connections.
- On Base event, debounce by homework date and send `homework_changed`.
- Implement Android fallback sync rules in Phase 5.

Success criteria:

- Editing Base causes a connected test client to receive `homework_changed`.

### Phase 4: Submit Results To Base

Goal: write completion result and photo back to the same Base row.

Tasks:

- Add multipart submission endpoint.
- Upload photo to Feishu as Base attachment.
- Update row fields: status, submitted time, overtime flag, photo attachment, note.
- Validate local `task_version` against current row version.
- Return conflict if the Base row is newer.

Success criteria:

- Submitting a task updates the same Base row.
- Parent can open the row in Feishu and inspect the photo.

### Phase 5: Android MVP

Goal: complete the tablet execution experience.

Tasks:

- Build today's homework list.
- Add local Room cache.
- Add 17:00-21:00 time window logic.
- Add local deadline reminders with AlarmManager.
- Add CameraX submission.
- Add pending submission queue.
- Add sync on app start, push event, 16:50, and manual refresh.

Success criteria:

- Tablet works through a full evening from synced assignments to photo submission.
- Network loss does not break local reminders or prevent taking a submission photo.

## Open Decisions

- Whether the tablet should use WebSocket only during 17:00-21:00 or keep a longer-lived connection.
- Whether the first version should support multiple photos per homework task.
- Whether parent review status should include `通过` and `不通过`, or whether `需重做` is enough.
- Whether to store a small local audit trail for start/pause/reminder events without syncing it to Feishu.

## Recommended First Step

Start with Phase 1 in `~/code/cyeam_web`.

Do not start Android implementation until the Feishu event intake and read-today-homework endpoint are working. Those two service capabilities define the real contract the tablet will consume.
