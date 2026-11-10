# Backend Call Contract v1 — VRTrainingAcademy

Status: **reviewed API map**, not all calls implemented.
Base: R0–R3 unified main. See `docs/audit/backend-api-contract-review-r4.md`.
The wire-level protocol MUST preserve a distinction between implemented server
routes and *proposed* native Quest endpoints; a Java service is not an API.

## Authentication contexts

| Principal | AuthN | Backend permission | Never trust |
|---|---|---|---|
| Teacher/admin | OIDC JWT + issuer/audience; tenant membership adapter | Manage classrooms and authorized collections | Caller-provided tenant, teacherSubject |
| Registered Quest | Future OAuth Device Authorization + sender-constrained access token, registered public key, revocation state | Assigned session, authorized lesson, scoped download | Request body deviceId or learner number as identity proof |
| Student | No personal login; supervised numeric selection within authorized session | Start learning attempt and record bounded events | Short number as cryptographic authentication |
| Publisher | Future backend service credential, content approvals and signing authority | Publish signed immutable content release | Public R0 demo catalog or arbitrary S3 URL |

## Currently callable endpoints

### Public browsing
`GET /api/v1/public/catalog?locale=kk-KZ`
- `locale`: one of `kk-KZ`, `ru-RU`, `en-US`; default `ru-RU`.
- Returns demo catalog; MUST NEVER imply paid entitlement.
- Valid anonymous read: 200; not a source of signed download grants.

### Grant request
`POST /api/v1/me/content/{contentId}/download-grants?platform=quest&runtimeBuildId=...`
- JWT subject and tenant_id come from verified token; content, platform,
  runtime are validated.
- Entitlement and published matching release are required; default DENY.
- On success, response has immutable releaseId, transport, expiresAt,
  base64 manifest, detached signature and list of artifact URL, size, SHA-256.
- **GAP:** not yet bound to live device key/revocation and signed publication.

### Backend proxy artifact
`GET /api/v1/me/content/{contentId}/artifacts/{artifactName}?platform=quest&runtimeBuildId=...`
- Enabled only when `VR_DELIVERY_TRANSPORT=BACKEND_PROXY`; requires JWT
  with tenant and renewed entitlement check on every request.
- Optional: `Range: bytes=N-`, `If-Range: "<artifact sha256>"`.
- 200 full body + strong ETag; 206 matching range + Content-Range;
  416 unsatisfiable Range with Content-Range `bytes */SIZE`.
- If-Range mismatch: 200 full body (safe restart). 64KiB streaming.
- **GAPS:** not a grant-specific 5-min bearer capability, no actual device
  proof or bandwidth quota; R1 JVM downloader requires a separate proxy
  transport with bearer injection and backend origin.

### Teacher classroom
`POST /api/v1/classrooms` with `{"title":"7A"}`
`POST /api/v1/classrooms/{id}/student-numbers` with `{"number":247}`
`POST /api/v1/classrooms/{id}/sessions` with legacy
`{"deviceId":"UUID","lessonId":"PHY-001","lessonRevision":"r1",
"startsAt":"ISO-8601","endsAt":"ISO-8601"}`
`PUT /api/v1/classrooms/{id}/sessions/{sessionId}/collection` with
`{"collectionId":"PHYSICS"}`
`GET /api/v1/classrooms/{id}/statistics/today`
- Requires persistence flag and verified teacher classroom membership.
- Without trust adapter, default authorization denies the operation.
- Collection assignment denied unless published/licensed compatible catalog
  adapter authorizes it; no reassignment after an attempt was created.
- **GAP:** legacy session planner takes lessonId before learner selects
  it; replace with versioned collection-first session create request.
- UTC-to-school-local date: configured IANA timezone; 30-day policy exists,
  but no automatic deletion worker.

## PROPOSED endpoints (not implemented — do not call on clients)

`POST /oauth/device_authorization`, `POST /oauth/token`
  - Authorization Server responsibilities per RFC 8628, not new Spring MVC
    controller copies of the standard.
`POST /api/v1/device-activations`
  - Quest creates Ed25519 key, starts activation and receives short code.
`POST /api/v1/me/devices/activation-approvals`
  - Teacher confirms short code after verified school membership.
`POST /api/v1/device-activations/{flowId}/complete`
  - Quest proves private-key possession and obtains active device binding.
`GET /api/v1/device/me/daily-sessions`
  - Only sessions assigned to the authenticated Quest; no caller deviceId.
`GET /api/v1/device/me/daily-sessions/{sessionId}/lessons?number=247&locale=kk-KZ`
  - Number is a selection, not identity proof; only enabled after actual
    device-bound authentication and active session check.
`POST /api/v1/device/me/daily-sessions/{sessionId}/attempts`
  - Body: `{"number":247,"locale":"kk-KZ","lessonId":"PHY-001"}`.
  - Backend resolves student UUID, tenant, collection, compatible approved
    release, active session, device and unique attempt. Reject forged IDs.
`POST /api/v1/device/me/attempts/{attemptId}/events:batch`
  - Idempotent event IDs, monotonic sequence, authoritative scoring and
    offline sync acknowledgement required. Never trust client scoreDelta.
`POST /api/v1/classrooms/{id}/daily-sessions`
  - Versioned teacher collection-first scheduling; class/device/lesson
    collection permission verified atomically.

## Native application transitions

```
UNREGISTERED
  -> DEVICE_ACTIVATION_PENDING -> DEVICE_AUTHENTICATED
  -> DAILY_SESSION_ASSIGNED -> STUDENT_NUMBER
  -> LEARNER_LANGUAGE -> AUTHORIZED_LESSON_LIST
  -> SELECTED_ATTEMPT -> SIGNED_DOWNLOAD_GRANT
  -> VERIFIED_CONTENT_INSTALLED -> UNREAL_LESSON_RUNNING
  -> OFFLINE_OUTBOX_COMMITTED -> BACKEND_EVENT_ACK
  -> TEACHER_DAILY_STATISTICS
```

## Error model for new endpoints

Recommended application/problem+json (`ProblemDetail`) shape:
```json
{
  "type": "about:blank",
  "title": "Forbidden",
  "status": 403,
  "detail": "Device or classroom permission denied",
  "instance": "/api/v1/device/me/daily-sessions"
}
```
- 400: malformed IDs, unsupported locale, invalid ranges/bodies.
- 401: missing/expired bearer or missing device-bound proof.
- 403: verified principal but not entitled/assigned/revoked device.
- 404: nonexistent or obscured unauthorized object, according to disclosure
  policy; avoid probing school roster.
- 409: session active/student collision, content revision mismatch, stale
  collection assignment.
- 410: expired offline capability (future; standardize consistently).
- 416: unsupported/unsatisfiable artifact Range.
- 429: code guessing/rate quota exceeded (future).

## Acceptance evidence

The R4 branch covers HTTP Range/ETag/If-Range selection by JUnit and verifies
teacher-assigned collection consistency through real PostgreSQL SQL tests.
The full Device Flow, OAuth identity, license persistence, actual RustFS and
native Quest E2E are NOT qualified by this branch.
