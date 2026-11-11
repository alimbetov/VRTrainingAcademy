# R5 — Quest Lesson Upload ↔ Unreal Runtime Agreement

## Scope and explicit boundary

This increment introduces a **real Spring multipart staging API**, a
fail-closed RustFS/S3 quarantine adapter, Java integrity/authorization tests
and a common **signed release v2 specification** with a Java/Python canonical
encoding vector. **This does not yet publish content or mount/download a
Quest lesson**. Native C++ verifier, UE Package Mount, device authorization
and certified release persistence are separate RED-first tasks.

The R1 JVM content manager remains the reference client. Its old
`SignedReleaseVerifier` V1 encoding uses Java modified-UTF8 and an
identifier-only entryLevel; V1 **cannot safely express**
`/Game/Lessons/Physics/Maps/L_Electricity`. We do not silently reuse V1
signatures as Quest V2. R2 Download Grant's published-release adapter
also needs an explicit V2-aware implementation before Quest delivery.

## Upload → stage (implemented)

`POST /api/v1/content/quest/staging` (multipart/form-data):
- `metadata`: JSON part matching
  `unreal/contracts/quest-lesson-upload.v1.example.json`.
- `artifact`: exactly named `.pak` file.
- Authentication: trusted OIDC JWT subject **plus**
  `QuestLessonUploadService.UploadAuthorization` membership authority.
  Client-supplied `organizationId` is NOT authorization.
- Validation: safe identities, exact UE 5.6 / Android_ASTC profile,
  exact Runtime build ID, safe Unreal level path, per-artifact maximum
  512 MiB, exact declared length and streamed SHA-256.
- RustFS object key:
  `quarantine/quest-lessons/{organizationId}/{releaseId}/{randomUUID}/{name}`.
  Object is untrusted and cannot be served by published download grants.
- Response: HTTP 202 with state `STAGED` and a quarantine object key.
  A STAGED file is never marked PUBLISHED or CERTIFIED.

Opt-in deployment settings:

```properties
vr.delivery.s3-enabled=true
vr.delivery.base-url=https://rustfs.example.internal
vr.upload.enabled=true
vr.upload.quarantine-bucket=vrta-quarantine
spring.servlet.multipart.max-file-size=512MB
spring.servlet.multipart.max-request-size=513MB
```

S3 endpoint/credentials, trusted OIDC issuer and audience, and a
**trusted** `UploadAuthorization` adapter must also be supplied. Without
an IAM adapter uploads are rejected. Do not set `vr.upload.enabled`
in production before membership, upload quotas, audit logging, metadata
lifecycle, cleanup and object-store retention policies are implemented.

**Important:** this is a bounded single-artifact MVP endpoint; large and
multi-container lessons require presigned multipart S3 upload with a
server-side finalization step and persisted upload sessions. It must not
pretend to support arbitrary multi-gigabyte lessons.

## Canonical Quest Release V2 (implemented in Java, vector checked in Python)

Authoring JSON is untrusted metadata. Only a **trusted publisher** may
issue a signed certification release after structural and native tests.

`SignedQuestReleaseV2.canonicalBytes` protocol:

```text
ASCII "VRTA-QUEST-RELEASE-V2\n"
for releaseId, lessonId, collectionId, platform,
    engineVersion, runtimeBuildId, entryLevel:
    uint32 big-endian ASCII byte length + ASCII bytes
uint32 big-endian artifact count (1..32)
for artifacts sorted by filename (ASCII ascending):
    uint32 big-endian filename length + ASCII filename
    uint64 big-endian artifact size
    raw 32 bytes SHA-256 digest
Ed25519 detached signature over these bytes
```

The pinned trusted publisher key MUST come from the installed Runtime
trust store, never from the uploaded manifest. V2 validates exact
`Android_ASTC`, `5.6`, `runtimeBuildId` and the trusted
`/Game/Lessons/...` level path. The example canonical bytes are 237
bytes and have SHA-256:
`18ff9138abf0831c7ffd2a33f0281b1cea2eb195e7b4469c443ad718332ae290`.

The authoring `runtimeMinVersion` and `installMode` flags are **not
security authorities**; the certified client must enforce its exact signed
runtime build and a separately vetted package-mount mode. Do not allow
unsigned extra JSON fields to alter eligibility or mount behavior.

## Required lifecycle (only STAGED implemented)

```text
RECEIVED → STAGED → VALIDATING → CERTIFICATION_PENDING
                              ↘ REJECTED
→ CERTIFIED → PUBLISHED → REVOKED
```

A release may enter `CERTIFIED` only with signed, immutable artifact
digests, trusted publish authority, verified compatible cooked level,
and recorded hardware/device profile results. Each changed artifact
hash, entryLevel or runtime build invalidates the previous certification.

## Runtime launch gates (NOT YET implemented)

1. Independently authenticate the Quest; check a valid organization
   license and learner permission. UUID and file path alone are not trust.
2. Request a device-scoped Download Grant for an immutable V2 release.
3. Retrieve a signed V2 descriptor and verify Ed25519 with pinned key.
4. Check **exact** runtimeBuildId/platform/engineVersion and approved
   package-mount configuration.
5. Stream download from authorized RustFS URL, resume safely and verify
   all file size+SHA-256 before atomic installation.
6. Mount cooked Pak/IoStore correctly for the real UE 5.6 Android build.
   Never assume Pak works if the editor actually built IoStore.
7. Resolve and open only the signed `entryLevel`; prohibit direct
   unverified level-path calls.
8. Reopen while offline under a bounded signed offline license.
9. Persist learner attempts and progress in a local outbox and replay
   with idempotent server-side acknowledgment.

`OpenDownloadedLesson()` remains **deliberately fail-closed** until the
native verifier and mounting implementation satisfy these gates.

## Test/acceptance evidence

| Capability | Gate | Evidence |
|---|---|---|
| Authorized bounded upload | Java JUnit | Included |
| Wrong size, SHA, filename, platform, entry path | Java JUnit | Included |
| Opt-in RustFS adapter | Java compile | Included, no live RustFS |
| Upload↔manifest↔Runtime build identity | Python / Java | Included |
| V2 signature detects altered entry path | Java Ed25519 JUnit | Included |
| Java↔Python canonical V2 vector | Two independent implementations | Included |
| Persistence/DB publication state machine | PostgreSQL integration | NOT IMPLEMENTED |
| Real RustFS multipart upload/recovery | Containers | NOT VERIFIED |
| V2 verifier in native Unreal C++ | Native C++ | NOT IMPLEMENTED |
| Android ASTC mount/load and device offline reopen | Physical Quest | NOT VERIFIED |

**Release decision:** staging foundation only; keep PR Draft and do not
advertise Quest lesson launch as functional until device UAT evidence.
