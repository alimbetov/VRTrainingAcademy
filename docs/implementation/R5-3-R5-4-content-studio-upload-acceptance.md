# R5.3 + R5.4 — Content Studio → Server-bound Lesson Upload

**Date:** 2026-11-11. **Status:** DRAFT / staging qualification, not published delivery.

## User Story exercised by code

1. OIDC login in `content-studio/` via Authorization Code + PKCE; the user is checked by Spring Resource Server and trusted database content membership.
2. World → Area → Subject navigation is read from scope-filtered R5.2 APIs, not the public sample catalog.
3. CONTENT_AUTHOR creates a DRAFT Lesson in selected Subject; database creates the exact placement within one transaction.
4. CONTENT_ADMIN can link a draft Lesson to additional Subject with permission on BOTH source and target Worlds.
5. Translations (`kk-KZ`, `ru-RU`, `en-US`), taxonomy transitions, optimistic ordering through R5.2.
6. On Lesson card, author picks a Pak artifact and trusted `entryLevel` candidate.
7. Frontend sends `POST /api/v1/admin/content/lessons/{lessonId}/uploads` with `Idempotency-Key` and **only** `{subjectId}` body. Backend checks existing DRAFT Lesson + server-owned placement, active content membership, and writes durable `uploadId` in PostgreSQL.
8. Frontend computes SHA-256, then `POST /api/v1/admin/content/uploads/{uploadId}/artifact` (multipart metadata + artifact).
9. Backend independently streams/rehashes, checks exact size and digest, places artifact in RustFS/S3 QUARANTINE, and commits state `STAGED` with digest+object key and an audit row.
10. Result is **STAGED only**. No publication, no granting Download Access, no native Quest launch claim.

## New DB and HTTP contracts

Migration: `backend/db/migrations/R5_4__lesson_upload_session.sql`.
States: CREATED → UPLOADING → STAGED | FAILED. Same actor+lesson+idempotency
key yields the same session; different selected Subject under reused key is
rejected. Every upload session is tied to existing `catalog_lesson`,
`catalog_subject` and `catalog_world` UUIDs.

Backend classes:
`platform.content.upload.LessonUploadSessions`,
`LessonUploadController`, `LessonUploadConfiguration`.

REST (OFF by default):
- `POST /api/v1/admin/content/lessons/{lessonId}/uploads` with
  `Idempotency-Key` (16..128 safe chars), JSON `{subjectId}` → 201 CREATED.
- `GET /api/v1/admin/content/uploads/{uploadId}` → state.
- `POST /api/v1/admin/content/uploads/{uploadId}/artifact` with JSON
  metadata part `{artifactName,sizeBytes,sha256Hex,runtimeBuildId,entryLevel}`
  and binary artifact part → 202 STAGED.
- NO `publish` or `certify` endpoint. Client cannot set
  `organizationId`, `collectionId`, `releaseId` or raw RustFS key.

Backend flags (private trusted environment; example values only):
```properties
vr.content.admin.enabled=true
vr.content.upload-sessions.enabled=true
vr.content.admin.jdbc-url=jdbc:postgresql://localhost:5432/vrtraining
vr.content.admin.jdbc-user=vr_admin
vr.content.admin.jdbc-password=...
vr.content.upload-sessions.jdbc-url=jdbc:postgresql://localhost:5432/vrtraining
vr.content.upload-sessions.jdbc-user=vr_admin
vr.content.upload-sessions.jdbc-password=...
vr.content.upload-sessions.quarantine-bucket=vrta-quarantine
spring.servlet.multipart.max-file-size=512MB
spring.servlet.multipart.max-request-size=513MB
```

S3Client is supplied by existing `vr.delivery.s3-enabled` configuration,
with proper RustFS endpoint and IAM credentials; require HTTPS/TLS.
Do NOT enable this on production with unrestricted admin DB credentials:
migration management, IAM provisioning, DB least-privilege, upload quota,
S3 lifecycle and GC, rate limits, malware scanning and observability must
be qualified independently.

Browser MVP deliberately limits files to **64 MiB** due to in-memory
Web Crypto SHA-256; backend caps to **512 MiB**, still uploads through
multipart/form-data (server temporary file + staging temp). Real large
VR lessons require chunked/presigned S3 multipart + server-side digest
finalization before general release.

## Integrity and failure boundaries

- UploadService always checks verified actor and scope, draft state and
  selected Subject placement on **server** before reserving bytes.
- Session ID is scoped to its original author; role revoke disables
  subsequent operations; no cross-owner GET.
- SHA mismatch, wrong size, corrupted Pak name and invalid runtime
  build/entry level never reach STAGED; upload errors mark FAILED.
- Object key uses **server-side UUID** + immutable lesson ID; no client
  supplies its own folder.
- DB transition STAGED and audit insert are atomic.
- If RustFS succeeded but DB finalization failed, an orphan object may
  remain under quarantine. **Required R5.4 hardening:** an explicit GC
  worker for orphan/expired uploads, recovery of interrupted UPLOADING,
  bounded retries, transactional cleanup and S3 verification.
- Server does not inspect whether uploaded Pak is truly cooked for UE
  Android_ASTC; certification is a later, mandatory trust gate.
- No demo/sample catalog is used for trusted publication.

## Automated evidence and remaining acceptance

R5.3: Vite React TypeScript production build, Vitest API bearer/403/lesson
endpoint tests, OIDC flow configured but not live IdP E2E.
R5.4: PostgreSQL16 integration tests cover exact owner/lesson/subject,
idempotency, forged actor, reject wrong subject, SHA-256 tamper, durable
STAGED and FAILED. MockMvc covers authenticated/unauthenticated upload
session creation.

**Not yet proven:** actual React browser with live IdP + real RustFS, user
role provisioning, hosted APK, multipart large files, certified V2
publisher, physical Quest Mount → OpenLevel and offline reopen.

**Follow-up:** R5.4 hardening → Content Collection domain →
immutable certification → Native Quest V2 delivery. No merge to main
or production feature enabling until corresponding gates pass.
