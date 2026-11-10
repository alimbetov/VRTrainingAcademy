# Unreal Experience Runtime Contracts v1

Status: DESIGN CONTRACT, not implemented API. Strict separation of public UI-facing subsystem contracts and backend HTTP wire contracts.

## Main components

- `UVRCatalogSubsystem`: hydrate from signed/validated cached snapshot or API; answer `GetWorlds(locale)`, `GetAreas(worldId)`, `GetSubjects(areaId)`, `GetLessons(subjectId, worldId)`, `GetLesson(id)`. Broadcast `CatalogReady(version)`, `CatalogRejected(reason)`.
- `UVRNavigationSubsystem`: breadcrumb stack, context and selection. Navigation never downloads content automatically.
- `UVRSessionSubsystem`: guest/device/learner scoped session; tokens never persisted as plain SaveGame; session ending erases transient learner context.
- `UVRContentSubsystem`: `ResolveDependencies(lessonId)`, `RequestInstall(lessonId)`, `CancelInstall(jobId)`, `GetInstallStatus(lessonId)`, `CheckAvailable(lessonId)`. Single-flight downloads per release.
- `UVRLearningSubsystem`: `StartLesson(lessonId)`, `SaveProgress(attemptId)`, `CompleteLesson(attemptId)`, `ExitToHub()`. One active attempt at a time.
- `IVRContentInstaller`: interface for `BundledInstaller` and `CookedChunkInstaller`; verifies manifest signature, checksums, runtime/platform and verified dependencies before exposing READY.
- `UInteractionComponent` + `BPI_Interactable`: shared input-agnostic contract for interact, grab, release, focus, and accessible alternatives.
- `IAssessmentRuntime`: deterministic scoring rules from published Lesson definition; no ad-hoc score trusted directly from a Blueprint.

## Catalog wire contract

GET /api/v1/public/catalog (R0 existing backend) is a legacy *flat demo* contract. **Do not assume it returns v1 taxonomy**.
Future scoped endpoint: GET /api/v1/catalog/snapshot?locale=ru-RU — versioned taxonomy snapshot.
See `contracts/catalog.snapshot.v1.example.json`. A backend adapter must evolve the R0 API before this endpoint exists.

`snapshotVersion` is monotonic for a published audience; `schemaVersion` is an integer with explicit supported range. An unsupported major schema is rejected without losing the last good cached snapshot.

## Contract records

World: id, titleKey, sortOrder, state.
Area: id, worldId, titleKey, sortOrder, state.
Subject: id, areaId, titleKey, sortOrder, state.
Lesson: id, titleKey, objectiveKeys[], experienceId, accessHint, curriculumFacets[], state.
Placement: worldId, areaId, subjectId, lessonId, sortOrder.
ExperienceDefinition: id, entryLevel, requiredModuleIds[], scenarioDefinitionId, assessmentDefinitionId.
ModuleRelease: id, moduleId, version, platform, runtimeBuildId, engineVersion, packageFormat, contentSha256, signatureRef.
Locale table: keys → published locale translations, separate from native Unreal UI localization.

If Lesson points to an Experience not bundled locally, the experience's required release IDs are fetched from licensed server manifest and checked against installed content.

## State machines

Catalog: EMPTY → LOADING → READY; failure → STALE_READY (if previous snapshot exists) or ERROR.
Install: NOT_INSTALLED → QUEUED → DOWNLOADING → VERIFYING → STAGED → MOUNTING → READY.
Failure during install: FAILED; cancellation: CANCELLED; a failed update cannot replace a working installed version.
Learning: IDLE → PREPARING → RUNNING → PAUSED → COMPLETED | ABORTED; results persisted locally before remote sync.

Events are emitted on game thread. Callbacks must be cancelled/ignored after subsystem teardown. Download bytes must stream to file, never allocate entire packages in memory. No user-controlled arbitrary asset path is accepted.

## Security

- Authentication is never only a login string: bearer session or restricted anonymous grant as appropriate.
- Entitlements and signed download grants are enforced by backend; local check is an offline policy convenience, not an authorization source for servers.
- Signed canonical release manifest, pinned public key and SHA-256 are separate checks.
- Presigned URLs are short lived and stripped from logs and local persistence.
- Local learner data and attempt logs follow tenant/session isolation and cleanup requirements.

## Acceptance tests

| ID | Contract | Expected |
|---|---|---|
| TAX-01 | Navigate world→area→subject→lesson | Stable sorted result, valid breadcrumbs |
| TAX-02 | Same lesson in two placements | One lesson ID, no duplicate assets |
| TAX-03 | Dangling reference, cycle, duplicate ID | Reject snapshot; retain last-good |
| TAX-04 | Missing locale | Deterministic published fallback |
| TAX-05 | Unavailable/locked lesson | Visible if configured, no launch/grant |
| CONT-01 | Installed module shared by 2 lessons | No duplicate download |
| CONT-02 | Corrupt update | Installed working version stays READY |
| CONT-03 | Runtime/platform mismatch | Reject before mounting |
| CONT-04 | Interrupted network | Download resumable/retryable, not launchable partial files |
| RUN-01 | Start / save / exit / reopen | Persists progress for correct local session |
| RUN-02 | No installed dependencies | No attempt is started |
| RUN-03 | Offline & permitted entitlement | Installed lesson reopens |
| VR-01 | Packaged Windows cooked chunk | Verified mount + OpenLevel |
| VR-02 | Physical Meta Quest APK | Verified mount + OpenLevel + restart/offline reopen |

VR-01 and VR-02 remain BLOCKED/UNPROVEN until real-device acceptance.
