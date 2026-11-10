# VRTrainingAcademy R4 — Backend API, Security & Wire Contract Audit

Audit baseline: `main@5870dba92c5d6dbcb4095665a8e1a61dc9b009dc`.
Target branch: `audit/backend-api-contract-hardening-r4`.
This is code and automated test review only; **not** a live Quest/RustFS/IdP penetration test.

## Endpoint inventory (actual Spring MVC)

| Method/path | Access today | Caller | Backend implementation |
|---|---|---|---|
| GET `/api/v1/public/catalog?locale=...` | Public read | Visitor | `PublicCatalogController` — demo only |
| POST `/api/v1/me/content/{contentId}/download-grants?platform=...&runtimeBuildId=...` | OAuth JWT + tenant claim + entitlement (deny-all without adapter) | Authorized downloader | `DownloadGrantController` |
| GET `/api/v1/me/content/{contentId}/artifacts/{artifactName}` | OAuth JWT + tenant claim + entitlement, only BACKEND_PROXY | Downloader | `BackendArtifactProxyController` |
| POST `/api/v1/classrooms` | Teacher JWT, persistence opt-in, membership port deny-all by default | Teacher | `TeacherClassroomController` |
| POST `/api/v1/classrooms/{id}/students` | Same; legacy nickname path | Teacher | `TeacherClassroomController` |
| POST `/api/v1/classrooms/{id}/student-numbers` | Same | Teacher | `TeacherClassroomController` |
| POST `/api/v1/classrooms/{id}/sessions` | Same; **legacy fixed lesson ID** | Teacher | `TeacherClassroomController` |
| GET `/api/v1/classrooms/{id}/statistics/today` | Same | Teacher | `TeacherClassroomController` |
| GET `/api/v1/classrooms/{id}/sessions/{sessionId}/statistics` | Same | Teacher | `TeacherClassroomController` |
| PUT `/api/v1/classrooms/{id}/sessions/{sessionId}/collection` | Teacher JWT, classroom persistence opt-in, content policy deny-all by default | Teacher | `TeacherCollectionAssignmentController` |

**Not present as REST routes:** OAuth 2.0 Device Authorization flow endpoints,
Quest activation start/complete, authenticated Quest session/roster/lesson/attempt
endpoints, event batch ingest, publisher/entitlement management APIs.
Code-level services and design documents must not be mistaken for callable API.

## Trust boundaries and observed findings

| Finding | Priority | Evidence and risk | Treatment |
|---|---|---|---|
| R4-01: Proxy missing strong ETag / If-Range | P0 | R1 downloader sends resume headers; R2 proxy originally does not send ETag | **Fixed in this branch**: SHA-derived strong ETag, If-Range mismatch falls back to 200, 206 only when matching, 416 Content-Range |
| R4-02: Selected lesson not bound to assigned collection at insert | P0 | `PostgresLearnerJourneyRepository` only checked `collection_id IS NOT NULL` | **Fixed**: require exact collection and block assignment changes after attempts exist |
| R4-03: No device proof-of-possession on download or new learning APIs | P0 | `DownloadGrantController` accepts JWT without checking registered Quest key, activation/revocation or device context | **Open**; do not add device endpoints until separate verified device authentication layer |
| R4-04: No persistent published release/entitlement authority | P0 | `DeliveryPolicyConfiguration`, `LessonJourneyConfiguration` return deny-all ports | **Open**; implement signed publisher + tenant/seat/license persistence, do not use public demo catalog |
| R4-05: Signed manifest content not reconciled against published artifact metadata | P0 | `PublishedReleasePort` accepts manifest/signature strings + unverified artifact list | **Open**; signature validation and object manifest integrity at publication and before grant |
| R4-06: Proxy grant TTL is informational only | P0 | Proxy GET uses OAuth + entitlement for each request, no grant-specific token or enforced expiry | **Open**; choose per-request entitlement API (renamed) or signed expiring grant token + proof |
| R4-07: Backend proxy auth and URL origin mismatch with R1 JVM downloader | P0 | Downloader only allows configured RustFS origin and doesn't send bearer; proxy is relative authenticated URL | **Open**; explicit dual transport clients plus refresh/expiry parity required |
| R4-08: Collection scheduling still demands lesson ID | P1 | `TeacherClassroomController.Schedule` needs `lessonId` and revision, while learner selects lesson later | **Open**; versioned collection-first scheduling API, deprecate old method after migration |
| R4-09: Scoring relies on client `scoreDelta` | P0 for assessed grade | `ProgressEvent` stores client-provided points | **Open**; use server-validated events and signed lesson assessment rules; never call it verified examination |
| R4-10: 30-day cleanup is policy only | P1 | No retention scheduler or background reconciliation | **Open**; protected offline events, timezone, archiving, legal hold |
| R4-11: Schema changes are hand-applied, not managed | P1 | Raw SQL `backend/db/migrations`, not Liquibase migrations | **Open**; sequential versioned Liquibase changelog, real migration/restart tests |
| R4-12: Teacher role and tenant membership not backed by real identity provider | P0 | Default `ClassroomAuthorization` denies; JWT tenant claim alone cannot create membership | **Open**; tenant membership/role lookup with active user status |
| R4-13: No native Quest client | P0 for release | `unreal/SourceSketch` has no `.uproject` or compiled UI/runtime | **Open**; native source, offline outbox, packaged Windows then Quest |

## Wire-level invariants (accepted)

- Backend Proxy must emit `ETag: "<sha256>"`, `Accept-Ranges: bytes`, and
  for a matching single Range `206` with `Content-Range` and exact length.
- If `If-Range` mismatches, ignore Range and return full `200` with ETag.
- Invalid/unsatisfiable supported Range returns `416`,
  `Content-Range: bytes */{size}`.
- No redirect/URL spoof, direct RustFS credential leakage or presigned URL logs.
- Every attempt must bind tenant, registered device, classroom number, assigned
  collection, selected lesson, locale, immutable published release and UUID.
- Assignment must not silently change after attempts have begun. A new
  assignment needs a **new daily session**, preserving historical attempts.
- Student number is selection, not identity proof. Another student's grades
  are never displayed on the Quest.
- Unauthorized state = deny, not demo fallback.
- Day-scoped statistics from school timezone and optional 30-day history
  must not merge attempts from different local days.

## Required RED → GREEN → acceptance phases

1. **R4.1** (this branch) Proxy Range/ETag tests and selected-attempt collection
   consistency tests on real PostgreSQL.
2. **R4.2** Resolve publisher schema and signed `Collection → Lesson → Release`
   identity, entitlements and publication validation; add real PostgreSQL tests.
3. **R4.3** OIDC admin + OAuth device authorization + device-bound access,
   revocation and REST endpoints; deny wrong tenant/device and replay.
4. **R4.4** Versioned collection-first sessions and bounded event batch
   ingestion; server-side assessment policy and offline reconciliation.
5. **R4.5** Direct/Proxy parity tests with actual RustFS + native packaged Quest
   client; crash/restart/offline open.
6. **R4.6** 30-day retention, migrations, operational telemetry and S2 review.

**Verdict:** Conditional Accept for the integrated foundation. Not release-ready.
Merge only after exact-head CI success and independent review of public wire
contracts; do not enable incomplete device/classroom endpoints by default.
