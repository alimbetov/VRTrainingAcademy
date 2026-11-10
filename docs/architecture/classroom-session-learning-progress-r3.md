# R3 — Classroom Session & Learning Progress

Status: domain contracts, test-first service foundation; not a production-integrated system.

## Design decision

One activated Quest may be shared by multiple students. No student email,
password, OAuth account, biometric identifier or hardware serial number required.

Three separate identities:
- **Organization**: school/licensee and billing + entitlement boundary
- **Device**: registered app instance with proof of possession of key
- **Student**: pseudonymous UUID, stable within a classroom, with teacher-editable nickname

Nickname only selects the learner; it does **not** authenticate the learner.
An exam/high-stakes grade needs teacher confirmation or a per-student short PIN.
The UI must never display other learners' statistics.

## Full workflow

1. Teacher signs in via OIDC website, creates class and nickname roster.
2. Teacher assigns a lesson revision and authorized device to an 8h-or-shorter session.
3. Registered device refreshes a **scoped** roster and signed session grant (future).
4. Student selects nickname on headset; session starts with a unique attempt UUID.
5. Native client emits bounded, versioned interaction/assessment events with
   globally unique event ID and monotonically increasing per-attempt sequence.
6. Offline outbox stores events transactionally, retries until acknowledged.
   Backend idempotently persists and derives statistics from verified events.
7. Teacher views per-student results for their own school/class only.
8. Device ends learner session and clears ephemeral student data; already
   installed VR packages remain in the separate device cache.

Student UUID must be stable across nickname changes. Uniqueness of a nickname is
class-scoped and case-normalized; if two students would have identical display
names, teacher must disambiguate them.

## Security boundaries

- Device activation + OAuth Device Flow establishes ownership by organization
  and a registered device key; device ID supplied as plain JSON is NOT proof.
- Teacher JWT must be bound to tenant membership (not just untrusted header).
- Device endpoints require a proof-of-possession device credential and a
  server-authorized session bound to device, tenant, lesson, expiration.
- Selecting nickname is **not** identity proof; classify results accordingly.
- No client-supplied score totals without validated events, assessment rules
  and server-side scoring policy. Reference service's scoreDelta exists only
  as pre-validation contract and MUST NOT be exposed directly to an untrusted
  client for production grades.
- Single headset can have only one actively mounted learner attempt at a time.
- Offline permissions require signed session manifests scoped by device, lesson
  and student roster, with an expiry and replay-safe reconciliation.
- Device revocation cannot be immediately effective while fully offline;
  offline lease expiry bounds that window.

## Database design — PostgreSQL, schema `education`

`education.classroom`: classroom_id UUID PK, tenant_id, teacher_subject, title.
`education.student_profile`: student_id UUID PK, classroom_id FK, nickname,
normalized_nickname, active, UNIQUE(classroom_id, normalized_nickname).
`education.classroom_session`: session_id UUID PK, classroom_id FK, device_id,
lesson_id, lesson_revision, start/end, active, created_by.
`education.learning_attempt`: attempt_id UUID PK, session_id FK,
student_id FK, device_id, lesson_revision, created_at.
`education.learning_event`: event_id UUID PK, attempt_id FK,
sequence, event_type, score_delta, occurred_at, received_at,
UNIQUE(attempt_id, sequence), collision == conflict.
`education.device_session_grant` (future): signed scoped grant, key ID,
expires_at, sync watermark, revoked.
`education.learning_outbox_ack` (future): delivery cursor/ack record.

PostgreSQL must use tenant-safe queries, foreign keys and transaction boundaries;
a simple FK does not prove tenant membership. Prefer explicit joins against
classroom tenant scope and row-level security when tenant topology warrants it.
Never persist student display name inside a signed device access token.

## Domain APIs — proposed

Teacher (JWT + trusted tenant and teacher class membership):
- POST /api/v1/classrooms
- POST /api/v1/classrooms/{id}/students
- POST /api/v1/classrooms/{id}/sessions
- GET  /api/v1/classrooms/{id}/sessions/{sessionId}/statistics

Device (proof-of-possession + scoped session grant):
- GET  /api/v1/device/sessions/{id}/roster
- POST /api/v1/device/sessions/{id}/attempts
- POST /api/v1/device/attempts/{id}/events:batch

The branch currently exposes **no routes** for these concepts to avoid
accidentally authorizing client-provided device IDs via ordinary user JWT.

## Acceptance gates

A1: one Quest, two sequential learners: distinct attempt UUIDs, no data crossover.
A2: cross-tenant roster/events/statistics rejected, regardless of forged IDs.
A3: duplicate event same payload ACK once; same eventId/different payload rejected.
A4: disconnect, offline 10+ events, restart, resume, idempotent sync.
A5: teacher edits nickname: historical attempts remain attached to student UUID.
A6: revoked device or expired offline grant cannot start new online attempts.
A7: three downloaded worlds survive learner switching; progress survives eviction.
A8: high-stakes assessment requires teacher verification; nick alone insufficient.
A9: repeated sequence, invalid event and forged assessment scoring rejected.
A10: real Quest and packaged Windows with physical classroom handoff.

## Next work

Add real PostgreSQL adapter with Liquibase and Testcontainers, trust-validated
device credential verifier, teacher portal and Unreal C++ bindings. This
foundation is intentionally narrower than the complete implementation.
