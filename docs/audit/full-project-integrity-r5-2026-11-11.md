# VRTrainingAcademy — Full-project integrity & consistency audit (R0–R5)

Date: **2026-11-11**. Audited exact source baseline
`feat/r5-quest-lesson-upload-contract@7e6dfa16178eb680b3b432d181e978718c157848`,
including ancestry from PR #10 and integrated R4 PR #9. Baseline
`main@5870dba92c5d6dbcb4095665a8e1a61dc9b009dc`.
GitHub compares R5 as **114 commits ahead of main**, behind **0**.
PR #7, #8, #9, #10, #11 remain distinct **Draft** PRs, not merged
to `main`.

## Audit verdict

**Architecture: CONDITIONALLY CONSISTENT. Production release: NO-GO.**

Modular monolith boundaries, security-first publishing, PostgreSQL
licensing, reference R1 verifier and R2 HTTP transport are reasonable.
The currently runnable upload-to-Quest path is **not integrated**:
`Upload → quarantine STAGED → [missing persistent qualification and publication]
→ [missing V2 published-release adapter and device grant]
→ [missing native V2 verifier/mount] → Quest playback`.

Passing CI does not establish native Unreal C++ compilation or an
Android/Quest DLC test. No physical Quest evidence has been supplied.
Separate readiness scores (expert assessments, not reliability statistics):
architecture 6.5/10, backend foundation 6/10, end-to-end upload-to-play 2/10,
autonomous Quest execution 1/10.

## Findings by priority

### P0 — Blocks safe release and cross-component end-to-end

| ID | Finding | Exact evidence | Acceptance gate |
|---|---|---|---|
| INT-01 | No canonical persisted Content Publication Registry. Upload returns `STAGED` but no durable `VALIDATING/CERTIFIED/PUBLISHED` lifecycle or link to licensing collection catalog | `QuestLessonUploadService.stage`; `DeliveryPolicyConfiguration.denyUnpublishedReleases`; `PublishedReleasePort` | PostgreSQL migration, state transitions with CAS/idempotency, certified immutable object identity, negative tests |
| INT-02 | Signed Quest Release **V2** is not carried through R2 Download Grant: `PublishedReleasePort` stores opaque `signedManifestBase64` and signature without protocol identity/entryLevel/UE build semantics; no concrete published adapter | `SignedQuestReleaseV2`, `PublishedReleasePort`, `DownloadGrantService` | Explicit V2 DTO/encoding and signature/protocol tests; R2 grant resolves only PUBLISHED + CERTIFIED |
| INT-03 | Quest device-bound identity is not end-to-end. Enrollment Ed25519 proves possession only at activation. Existing grant endpoint authorizes owner web JWT, not Quest device session | `DeviceActivationController`, `DownloadGrantController`, `LicensedOwnerDownloadEntitlement` | Sender-constrained Quest credentials, per-call device/org/license/seat validation and revocation tests |
| INT-04 | Native Unreal Runtime cannot launch downloaded content: `OpenDownloadedLesson` intentionally always returns `false`, no native signature verifier, downloader, atomic installer or Android mount | `unreal/VRTrainingQuest/.../VRTAQuestGameInstance.cpp` | UBT build, Android ASTC build, real signed content `Download→Verify→Mount→OpenLevel` acceptance |
| INT-05 | Offline license, lesson state and progress outbox are not implemented on Quest | `OfflineSessionPermit` is a server contract, native game instance has no offline persistence/lease and progress sync | Device reboot/no-network replay, lease expiry, crash recovery and ordered idempotent sync |
| INT-06 | Actual hardware qualification evidence absent and required native GitHub jobs are skipped unless dedicated UE runner configured | `.github/workflows/quest-runtime-smoke.yml`; no certified DLC smoke map or physical device artifacts | Native C++ + Android UAT + ADB logs on supported Quest; separate hardware gate |
| INT-07 | Current R3 lesson/session model remains partly lesson-first instead of collection-first, and signed V2 release is not the immutable lesson revision authority | `ClassroomSessionService.schedule(lessonId, revision)`; `PostgresLearnerJourneyRepository.beginSelectedAttempt` persists selected release alongside session revision | Collection-first session schema, selected immutable V2 release as attempt authority, real catalog validation |

### P1 — Functional/security/operational consistency

| ID | Finding | Evidence | Improvement |
|---|---|---|---|
| INT-08 | Upload data not persisted in DB and quarantine object key is returned to caller; no upload id, resumable job, promotion audit or object retention | `QuestLessonUploadService.Receipt`, S3 `putObject` | Opaque `uploadId`, persistence, retention/cleanup, idempotency and operator audit |
| INT-09 | Whole multipart payload may be accepted/spooled before membership check; two staging copies (multipart temp + app temp), no service-level request quotas, disk reservations or concurrency caps | Spring `@RequestPart MultipartFile`; `Files.createTempFile` | Edge/body limits, early trusted auth, quotas, rate limits, disk budget and streaming presigned multipart for large resources |
| INT-10 | One-file upload supported, while actual Unreal Android packaging may emit Pak plus IoStore `.utoc/.ucas`; schema insists on `.pak` and one artifact per upload. No common cook profile | `QuestLessonUploadService.Request`, `SignedQuestReleaseV2.Artifact`, `quest-android-package-smoke.sh` | Freeze Pak-only Android cook mode **or** support versioned multi-artifact IoStore; native mount proof |
| INT-11 | V2 signature excludes `organizationId`, `runtimeMinVersion`, `installMode` and qualification profile; fields used for distribution/compatibility cannot be trusted unless separately signed | `SignedQuestReleaseV2.canonicalBytes`, `quest-lesson-package.v1.example.json` | Define authoritative signed profile + explicitly non-authoritative authoring metadata; prohibit grant relying on unsigned knobs |
| INT-12 | Controller checks file equality before metadata null-handling; 500 possible for bad multipart data; no consistent API ProblemDetail or per-upload exception mapping for S3 unchecked failures | `QuestLessonUploadController.upload` | Robust validation, 4xx/5xx handling and multipart HTTP contract tests |
| INT-13 | Organization identity and teacher membership remain fragmented: licensing UUID vs education string tenant, auth ports default deny; upload has no concrete trust-backed `UploadAuthorization` adapter | `LicensedOwnerDownloadEntitlement`, `ClassroomSessionService`, `QuestLessonUploadConfiguration` | Canonical organization UUID + trusted roles/membership + multi-tenant isolation tests |
| INT-14 | PostgreSQL migrations are reference SQL rather than an independently qualified ordered Liquibase/Flyway application lifecycle; adapters use DriverManager | `backend/db/migrations/R3* R4*`, `PostgresLearnerJourneyRepository` | Transactional versioned migrations, pooled DataSource, fresh/upgrade/rollback test |
| INT-15 | UE version pinned to 5.6 but dev toolchain/Quest profiles not qualified; compatible `EngineAssociation` and `runtimeBuildId` are structural, not cooked content compatibility proof | `VRTrainingQuest.uproject`, `VRTAQuestGameInstance.cpp`, `SignedQuestReleaseV2` | Approved engine+Android SDK+NDK+Quest device matrix and exact native cook/mount smoke |
| INT-16 | Classroom scoreDelta accepted from caller and cross-tenant/attempt ownership require trusted device auth; no server-authoritative assessment scorer | `ClassroomSessionService.record` | Signed curriculum/scoring contract, validated event semantics, tamper/replay/out-of-order tests |
| INT-17 | Public activation begin/complete lacks demonstrated HTTP throttling, operational abuse limits and observability | `DeviceActivationController`, `DeliverySecurityConfiguration` | Rate limits, registration attempt audit, metrics/alerts and malicious concurrency tests |
| INT-18 | Test reporting permits overall workflow SUCCESS while native compile/packaging jobs are SKIPPED; avoid branch protection treating this as VR quality | `quest-runtime-smoke.yml` | Dedicated mandatory non-skipped native qualifying status, separate hardware release gate |

### P2 — Maintainability and clarity

| ID | Finding | Recommendation |
|---|---|---|
| INT-19 | Parallel signing formats R1 V1 vs Quest V2 create ambiguity unless protocol version is explicit in grant | Use versioned envelope `signatureProtocol=VRTA-QUEST-RELEASE-V2` + cross-language golden vectors |
| INT-20 | Native C++ bundled-level path check is weaker than Java upload regex; `OpenBundledLessonOffline` returns true after dispatch rather than after successful load | Share exact path grammar, return async state `LAUNCHED/FAILED` and assert level-load event |
| INT-21 | R5 upload marks 202 STAGED without storing immutable DB receipt; retries create a new random S3 key each time | Idempotency-Key and unique (org,release,manifest digest) DB constraints |

## Dependency graph and domain authority

```text
Trusted OIDC + Organization membership
      |
      +--> ContentAuthoring -> UploadSession -> RustFS QUARANTINE (never public)
      |                            |
      |                      Validation Worker
      |                            |
      |                      Signed Candidate V2
      |                            |
      |                 UE / Quest native qualification
      |                            |
      |                    Publisher Approval
      |                            |
      |                   PUBLISHED Release Registry
      |                            |
      +--> Licensing/Org -> Entitlements -> Quest Download Grant
                                               |
                                     RustFS immutable release
                                               |
                              Native V2 verifier + atomic installer
                                               |
                                 Unreal Mount -> OpenLevel
                                               |
                         Offline license + progress outbox -> R3 sync
```

**Single authority rule:** Collection and lesson identity from Content
Domain, organization identity/membership from Platform, authentication
from trusted IdP and device PoP, immutable package from Publisher,
classroom/attempt authority from Education, file bytes from RustFS.
Untrusted upload JSON and a local folder marker are never authority.

## CI evidence and limits

Exact source HEAD `7e6dfa16` passed:
- backend Java 21 + PostgreSQL16 + Python fixture workflow
  [run 42951930353](https://github.com/alimbetov/VRTrainingAcademy/actions/runs/42951930353)
- Quest contract smoke (Python + bash syntax)
  [run 42951930332](https://github.com/alimbetov/VRTrainingAcademy/actions/runs/42951930332)

Native C++ UBT and Android ASTC jobs remain **SKIPPED** without
licensed self-hosted UE 5.6 runner and associated CI variables.
No live RustFS deployment, actual upload through OIDC/HTTP, actual
UE cooked artifact, physical Quest or offline reboot has been certified.

## Recommended execution sequence

**Gate S0** — Keep PR #11 Draft, preserve PR #10 dependency. Fix transport
and scope ambiguity, normalize published release version, remove
potential upload 500s; add HTTP/body-limit and negative authorization
tests. Do not merge chained Draft PRs out of order.

**Gate S1** — Implement PostgreSQL UploadSession/Artifact/Release/Certification
tables, migrations, replay-safe transitions, verified metadata and
quarantine finalization, actual RustFS container acceptance.

**Gate S2** — Add trusted IAM owner/author permissions and concrete
published collection catalog. Implement V2-aware R2 published-release
adapter with a strictly separated Quest device grant.

**Gate S3** — Build the native C++ signed V2 verifier, resumable downloader,
atomic install/registry, Pak-vs-IoStore handling and verified mount.

**Gate S4** — Physically qualify one source-built sample lesson on the
supported Quest model: download, mount, load, offline restart, corrupted
file, revoked/expired lease, scoring sync. Store exact artifact SHA,
APK SHA, Android/Quest firmware, UE/NDK build identity and logs.

**Do not certify uploads automatically** until S1–S4 pass on actual
supported device/profile. Successful CI is required but not sufficient.
