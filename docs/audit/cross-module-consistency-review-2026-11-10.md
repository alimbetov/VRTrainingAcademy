# VRTrainingAcademy — cross-module consistency audit

Date: **2026-11-10**. Scope: GitHub code and CI, not live browser/RustFS/Quest.
Audited: `main@5870dba9`, Draft PR #7 `9f563311`, Draft PR #8 `827bcf1c`.
Status: **NOT READY TO MERGE R4 AS A COHERENT PRODUCT**.
Tests on PR #8 latest SHA are green, but passing scoped tests is not full end-to-end proof.

## Repository integration finding (most urgent)

Both PR #7 and PR #8 are open **independent drafts targeting main**.
PR #7 is 16 commits ahead of main and fixes:
- `BackendArtifactProxyController`: stable SHA-256 ETag, If-Range, 416.
- `NumberLanguageLessonJourney` / `PostgresLearnerJourneyRepository`:
  binding the exact assigned collection to each attempt; no reassignment after
  attempts; hide lesson list outside active window.
PR #8 is 60 commits ahead of main, **does not contain those changes**.
Its existing `BackendArtifactProxyController` still lacks ETag/If-Range;
`PostgresLearnerJourneyRepository` checks only non-null collection and allows
reassignment after attempts. Merge integration must be deterministic, tested,
and based on one common branch before either is marked production-ready.

## Severity-ranked findings

| ID | Priority | Module / evidence | Required correction |
|---|---|---|---|
| CON-01 | P0 | PR #7 changes missing in PR #8 | Incorporate both branches in one integration branch; resolve tests and exact-head CI |
| CON-02 | P0 | R3 `schedule` requires `lessonId` + revision; R3 learner chooses lesson after number+locale, with session `collection_id` assigned separately | Replace legacy fixed-lesson scheduling with versioned collection-first session creation; pin lesson revision/release on attempt |
| CON-03 | P0 | Device activation proves Ed25519 during registration, but there is no sender-constrained device authentication on future REST calls | Implement verified registered-device principal, expiry/revocation/seat checks for all Quest APIs, never accept client-provided UUID alone |
| CON-04 | P0 | `PublishedReleasePort`, `PublishedContentCollectionPort`, `AuthorizedLessonCatalog`, `LicensableCollectionCatalog` lack live publication adapter | One immutable publication authority with explicit collection→lesson→release→manifest→artifact integrity and licensing |
| CON-05 | P0 | Download Grant R2 owner JWT vs intended Quest download identity | Versioned device-bound grant route using verified Quest principal, independent of owner web grant; same R2 delivery transport |
| CON-06 | P0 | Registered-device active seat check is online only; offline signed lease/revocation policy absent | Bounded device-bound offline lease, replay protection, expiration and time rollback tests |
| CON-07 | P0 | Public activation begin/complete is feature-gated, not rate-limited. Code flow has attempt cap but start flooding is still possible | Edge rate limits, abuse telemetry, quotas and cleanup; keep HTTP flag OFF |
| CON-08 | P1 | Owner subject and `organizationId` are separate; Education classroom `tenantId` remains free-form, while licensed activation requires UUID.toString | Canonical OrganizationId across Platform/Education, migration/backfill and scoped IAM membership |
| CON-09 | P1 | `PostgresLicenseStore.entitlements()` selects one active license by priority and latest expiration; multiple overlapping commercial licenses are allowed | Explicit license policy for renewal/add-on/downgrade, seat/collection merging vs replacement, unique effective decision and audit |
| CON-10 | P1 | Organization owner exists as a single `owner_subject`, no teacher memberships or transfers | Membership model with OWNER/ADMIN/TEACHER, invited user lifecycle, SSO integration and ownership transfer |
| CON-11 | P1 | Teacher daily stats + 30-day retention, but retention worker and offline outbox are not implemented | Define per-attempt aggregation, timezone per school, guarded retention with delayed sync |
| CON-12 | P1 | SQL in `backend/db/migrations` is manually applied; tests use create-table-if-not-exists replacements | Liquibase/Flyway migration history, clean install and upgrade from main using a fresh db, rollback qualification |
| CON-13 | P1 | JDBC DriverManager per operation, some multi-statement reads across separate connections | DataSource/Hikari, `@Transactional` boundary ownership, bounded DB timeouts and retries; ensure consistent snapshots |
| CON-14 | P1 | `classroom_session.lesson_revision` may be used as learner attempt revision even when the learner picks a different lesson | Derive lesson revision from the signed published release, store immutable release snapshot on attempt |
| CON-15 | P1 | `ClassroomSessionService.record()` accepts client `scoreDelta` for training stats | Server-side scoring rules, event-type whitelist, sequence and timestamp integrity |
| CON-16 | P1 | Some HTTP layer errors are mapped ad hoc, while SQL/data conflicts may surface as 500 | Versioned ProblemDetail errors and explicit 400/401/403/404/409/410/416/429 mapping |
| CON-17 | P2 | R1 Java `VRContentManager` is stored in backend tree as a device-side reference; Unreal contains sketches only | Move behind a shared protocol package and port native code, with contract/golden vectors; avoid treating JVM code as compiled Quest runtime |
| CON-18 | P2 | CI runs Java 21, PostgreSQL 16 and Python JSON fixtures; no real RustFS, OIDC issuer or UE build | Add Testcontainers or Docker E2E, signed artifact/Range tests, release packaging and real Quest UAT |

## Component consistency matrix

| Flow | Current integration | Status |
|---|---|---|
| Verified OIDC user → Organization → Trial | Controller, service and PostgreSQL reference, feature OFF by default | PARTIAL |
| Organization → manual commercial license | Atomic approval and tests; trusted admin adapter default DENY | PARTIAL |
| License → Quest activation seats | PostgreSQL seat serialization and current-cap check; enrollment opt-in | PARTIAL |
| Quest proof → authenticated Quest requests | Only registration proof; no device principal for later requests | BLOCKED |
| License → published collection | Interfaces + default-deny stubs; no real publisher | BLOCKED |
| Collection → selectable lesson → immutable release | Domain policy and lesson model; no durable publication authority | BLOCKED |
| Learner number → language → lesson attempt | Domain/SQL contract, no trusted device REST; legacy fixed lesson scheduling | PARTIAL |
| Active license → owner R2 Download Grant | Unit bridge, subject/tenant enforcement | PARTIAL |
| Verified Quest → R2 Download Grant | Domain check, no sender-constrained HTTP grant | BLOCKED |
| R2 Backend Proxy → resumable R1 downloader | Fixed only in separate PR #7, not integrated in PR #8 | CONFLICT |
| Verified package → running Unreal VR lesson | JVM installer/reference and JSON fixtures only | BLOCKED |
| Learning events → daily stats / 30-day retention | Database events/daily selection; no retention worker/offline-native reconciliation | PARTIAL |

## Required execution sequence (test first)

**S0 Branch convergence**
- Create `integration/r4-backend-consistency` from current main.
- Bring PR #7 and PR #8 commits together; fix conflicts without losing R1/R2.
- Run exact-HEAD CI and issue source-compatibility report.
- Only then supersede/close duplicated PRs or merge agreed integration PR.

**S1 Domain and database contract**
- Freeze canonical UUID Organization identity, school membership, classroom
  collection-first scheduling, lesson/release identity, and effective license.
- RED tests for cross-tenant ID mismatch, attempt assignment changes, 2 active
  commercial licenses, license downgrade during offline use.

**S2 Security authorization**
- End-to-end mocked/real IdP OIDC, device-bound proof and revoked-device grants.
- Assert 401 for absent proof, 403/404 for wrong organization/device,
  and no authenticated student identity from number alone.

**S3 Published content and transport**
- PostgreSQL published registry, signed release manifest and object evidence.
- Reuse R2 Direct RustFS and Backend Proxy; test 200/206/416 ETag/If-Range,
  short TTL and expiry/revocation.

**S4 Full journey**
- Owner registration → Trial → activate two Quest → assign classroom collection →
  number → locale → lesson → attempt → grant → verify/install → events →
  teacher today report; add database and API replay/concurrency cases.
- Real Quest and RustFS are mandatory for production qualification.

## Verdict

**Foundation architecture: ACCEPT WITH CONDITIONS.**
**Cross-module integration: NOT YET QUALIFIED.**
**Production deployment and automatic merge: NO-GO.**

Preserve existing signed loader and R2 grants; correct adapters and trust
boundaries rather than developing parallel delivery/identity mechanisms.
