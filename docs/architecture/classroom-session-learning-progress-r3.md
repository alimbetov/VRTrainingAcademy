# R3 — Classroom Session & Learning Progress

Status: executable Java domain contracts, opt-in PostgreSQL storage/teacher API, offline permit signing and device activation adapters; **not a production-integrated VR application**.

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

Teacher management routes are implemented in `TeacherClassroomController`, but
`VR_CLASSROOM_PERSISTENCE_ENABLED=false` by default. Even if explicitly
enabled, `ClassroomAuthorization` denies all actions unless an independently
verified organization/teacher-membership adapter is installed. **Device roster,
attempt and event endpoints are NOT exposed:** ordinary user JWT must never
impersonate a registered Quest. That boundary requires a real device
sender-constrained credential verifier and a scoped session/attempt capability.

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

### Implemented in R3 Draft

- `ClassroomSessionService`: scoped classroom roster, session schedule, nickname
  selection, UUID attempts, idempotent events and teacher statistics.
- `PostgresClassroomStore`: parameterized JDBC persistence and event collision
  checks; PostgreSQL 16 service integration tests in GitHub Actions.
- `TeacherClassroomController`: OIDC JWT teacher API **disabled by default**.
- `OfflineSessionPermit`: signed Ed25519 permit bound to device, tenant,
  lesson, session, roster and an 8-hour-or-shorter expiration. A real Quest
  client must use a pinned verification key and protect against local clock
  manipulation. Golden vectors/C++ port still required.
- `DeviceActivationService`: one-time short code confirmed by authenticated
  administrator and Ed25519 proof by the Quest; HMAC-SHA256 server pepper.
- `PostgresDeviceActivationStore`: atomic approval/activation with 10-attempt
  cap, revocation, registered public keys. No public Device Activation API yet.
- `unreal/contracts/classroom.session.v1.example.json`: roster fixture,
  `unreal/tests/test_classroom_contract.py` plus C++ noncompiling sketch.
- `backend/db/migrations/R3__classroom_learning_foundation.sql` and
  `R3_2__device_activation_foundation.sql`: reviewed SQL scripts, **manual
  application currently**; no Liquibase integration.
- `backend/.env.classroom-device.example`: secure disabled-default settings.

### Remaining deployment/security blockers

1. OIDC Authorization Server Device Flow endpoints and teacher UI for code entry.
2. Real organization membership authority for teacher and device activation.
3. Device sender-constrained access token or challenge authorization for every
   device request, with key rotation and active/revoked checks.
4. Trustworthy server-generated assessment scoring; never accept client
   scoreDelta as authoritative evidence for high-stakes grades.
5. Native Unreal UI: nickname picker, session switch and private local event
   outbox with restart-safe acknowledgements and one active learner at a time.
6. Signed offline session delivery, time rollback prevention, expiry and
   device revocation policy, replay and outbox synchronization tests.
7. Liquibase rollout with migration ordering, rollback strategy, real DB
   connection pool, backups and privacy retention/pseudonymization controls.
8. End-to-end teacher portal + packaged Windows + physical Quest acceptance.

**Do not enable production access or merge this as a finished classroom
authorization feature.** A passing SQL/Java CI is foundation qualification only.
