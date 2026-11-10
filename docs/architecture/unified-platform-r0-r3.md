# VRTrainingAcademy — Unified Platform Foundation R0–R3

Status: **Integrated foundation**, not a production-qualified Quest application.
Baseline: R0 taxonomy → R1 signed content installer → R2 dual transport →
R3 device activation / classroom / learner progress.
**Canonical implementation branch after integration: `main`.**

## Architecture and ownership

| Bounded context | Backend ownership | Native / browser ownership |
|---|---|---|
| Platform Identity | Organization membership, device activation, key registry, revocation | Teacher OIDC browser approval, Quest device key possession |
| Content Delivery | Signed published releases, entitlement check, short-lived Download Grant | Local download, signature/SHA-256 verification, atomic install |
| Education | Classroom, numeric student slots, assigned collection, sessions, attempts, progress | Number keypad → locale → available lessons → learning experience |
| Experience Runtime | No rendering or game-loop logic on server | Unreal C++/UMG, platform-specific cooked content, offline outbox |

The Platform must not import or query Education tables; Education accesses
Platform through designated interfaces and authorized APIs. A file being in
`backend/src/main/java` does NOT make it a server-side service:
`platform.delivery.VRContentManager` is a **JVM device-side reference**, not
a Spring-managed Quest downloader.

## End-to-end intended product workflow

1. Teacher/admin logs into website using an OIDC authorization server.
2. Quest displays short-lived activation code; authorized school admin approves.
   Device proves Ed25519 private-key possession and receives server-assigned UUID.
   **OAuth 2.0 Device Authorization Flow is an integration target, not an
   implemented authorization-server flow in this repository.**
3. Teacher prepares classroom and registers numeric student slots (100–9999).
   Names, nicknames, student login/password are **not** required.
4. Teacher schedules a daily session on a registered Quest and assigns a
   licensed/published collection. Today-only reporting uses school IANA timezone.
5. Student enters number, chooses `kk-KZ` / `ru-RU` / `en-US`, views
   compatible, published, entitled lessons within the assigned collection.
6. Student selects lesson. Backend revalidates device, class, tenant,
   collection, locale, publication and entitlement, then creates attempt
   tied to UUID student, device, session, lesson and immutable release.
7. Client checks local verified release inventory; if absent, obtains a
   short-lived **R2** Download Grant and artifact URLs from Backend:
   - `DIRECT_RUSTFS` (default): direct S3-presigned artifact GET
   - `BACKEND_PROXY` (optional): authenticated Backend HTTP Range stream
8. Native Quest content manager verifies canonical Ed25519 signed manifest,
   platform/runtime compatibility, artifact size and SHA-256. Then stages,
   commits and mounts only compatible cooked assets.
9. Native offline outbox persists scored interactions atomically before
   acknowledging learner, retries by UUID event ID and attempt sequence.
10. Teacher reads **today's** statistics by numbered student. Prior-day
    records may remain for **up to 30 days** for sync/reconciliation;
    automatic retention worker is **not yet built**.

## Existing code that must be REUSED rather than rewritten

| Component | Existing source | Actual status |
|---|---|---|
| Public demo catalog | `platform.catalog.PublicCatalogController` | Working R0 sample only; NOT authorized publication |
| Signed release checks | `platform.delivery.SignedReleaseVerifier` | JVM tests pass |
| Range download | `platform.delivery.RustFsRangeDownloader` | JVM reference, RustFS origin only |
| Local install/cache | `platform.delivery.VRContentManager`, `AtomicReleaseInstaller`, `LocalReleaseRegistry` | JVM reference, NOT Unreal native |
| Download authorization | `platform.delivery.grant.DownloadGrantService` | Contract + direct/proxy backend code; missing live entitlement/publication adapters |
| RustFS proxy | `platform.delivery.grant.BackendArtifactProxyController` | Spring MVC code, real S3/Quest E2E missing |
| Device activation | `platform.device.DeviceActivationService`, `PostgresDeviceActivationStore` | One-use approvals/key proof; no web/Quest auth endpoints |
| School classroom and attempts | `education.classroom.ClassroomSessionService`, `PostgresClassroomStore` | Tested backend, default-deny auth |
| Numeric learner / locale / lesson | `NumberLanguageLessonJourney`, `PostgresLearnerJourneyRepository` | Domain & PostgreSQL checks; no trusted device REST yet |
| Assigned collection | `TeacherCollectionAssignmentService` | Permission gate; published collection authority is deny-all |
| Offline session proof | `OfflineSessionPermit` | Signed Java contract; not a native Quest implementation |
| Unreal | `unreal/contracts`, `unreal/SourceSketch`, `unreal/tests` | Fixtures/sketches, **no .uproject, no real UMG or cooked scene** |

## Canonical contracts and invariants

- **One classroom number ≠ authentication.** UUID is authoritative; numeric
  selection is suitable for supervised low-stakes training only.
- **Tenant / device identity** is derived from verified claims and possession
  of registered device private key, not JSON `deviceId`.
- **Collection assignment** is teacher-managed, lesson selection is learner-
  managed per attempt, language is per attempt (never global Quest locale).
- **Content identity:** `collectionId → lessonId → published immutable
  releaseId → manifest → artifact hashes`. Validate this chain at every
  grant; keep Content Domain as source of truth, not public demo catalog.
- **Storage ownership:** binary cooked files in RustFS / per-device cache;
  learning events and progress in Education PostgreSQL / independent local
  outbox. LRU removal must never destroy assessment history.
- **Default-deny:** without trusted membership/publisher/entitlement ports,
  online device authorization, collection assignments and downloads remain
  unavailable. Do not silently enable via examples.
- **Dates:** `Asia/Almaty` is development default. Production organization
  timezone must be persisted; daily statistics reset at school-local midnight.
- **Retention:** 30-day **policy**, not an implemented automatic deletion job.
  No unacknowledged offline evidence silently discarded.
- **Native application trust:** cooked assets match runtime/platform, require
  signed manifests. Do not download and execute arbitrary native code.

## Blockers before production/physical Quest claim

**P0**
1. Trust-backed OIDC authorization + classroom teacher membership; OAuth
   Device Authorization endpoints and actual Quest activation exchange.
2. Device sender-constrained credentials, live activation/revocation,
   tenant/device/session auth on new device-specific REST API.
3. Persistent **Content Domain** publication, immutable signed manifests,
   collection/lesson/release ownership and tenant-entitlement adapters;
   eliminate default-deny stubs only after tests.
4. R2 grant ↔ native content downloader protocol parity: expiry, URL refresh,
   proxy Bearer authentication, ETag/If-Range semantics, replay and revocation.
5. True packaged Unreal Windows & physical Quest code for UI, content
   mount, signed offline permits and transactional outbox/reopen.
6. Real PostgreSQL + RustFS E2E, tenant-boundary and device security tests.

**P1**
7. Content identity versioning/foreign keys across collection/lesson/revision/
   release, transaction race tests and single active student per Quest.
8. Server-authoritative assessment scoring and constrained event contracts.
9. 30-day retention job, late offline sync, local-clock rollback and privacy.
10. Pooled JDBC, Liquibase migration sequencing, observability, bandwidth/
    storage quotas, publication lifecycle and disaster recovery.

## Verification / merge classification

GitHub Actions runs `unreal/tests/test_*.py` and
`cd backend && mvn -B --no-transfer-progress clean verify`, using
PostgreSQL 16. Green CI qualifies the **Java contract/reference scope only**.
No Quest/Windows package, RustFS instance or live IDP is part of these CI tests.

**Merge policy:** integrating these modules into `main` is appropriate as a
versioned, default-deny FOUNDATION, but never label that merge as a full
production deployment or completed headset learning experience.
