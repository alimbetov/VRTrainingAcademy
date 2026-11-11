# R5.2 — Content Administration API, trusted membership and audit

**Status:** Draft implementation, requires independent IAM/schema/security review.  
**Base:** R5.1 PostgreSQL Content Catalog (PR #12).  
**Spec:** `docs/specs/R5-admin-content-studio-catalog-upload-publication-tz-v1.md`.

## Implemented

- `backend/db/migrations/R5_2__content_administration.sql`
  stores `content.admin_membership` and `content.admin_audit`.
- OIDC JWT identity is supplied by Spring Resource Server and validated
  using the existing issuer/audience JWT decoder. Client-provided role,
  actor, organization, tenant and scope do **not** grant permission.
- Membership is queried within the same PostgreSQL transaction as
  each mutation and its audit event.
- `PLATFORM_ADMIN` may create global World; `CONTENT_ADMIN` may
  create Area/Subject, translate, reorder and transition in a World
  granted through trustworthy `scope_world_id`; `CONTENT_AUTHOR` may
  create DRAFT Lesson and update authorized lesson translations.
- A role with `organization_id` populated is **not accepted by the
  global administration adapter**: prevents tenant members from writing
  global catalog until explicit organization-scoped Content Domain rules
  are approved. `ORG_OWNER` is not implicitly `CONTENT_ADMIN`.
- Exact Subject placement is created transactionally with Lesson,
  plus an explicit extra placement endpoint checking the existing and
  destination World scopes. Cross-world permissions are required.
- Scoping of all tree reads and Subject lessons; unauthorized actors
  cannot enumerate all categories through this API.
- Taxonomy states: DRAFT→PUBLISHED (three locale titles required,
  published parent for Area/Subject), DRAFT/PUBLISHED→ARCHIVED
  (published descendants prevent parent archive). Lesson only:
  DRAFT→READY_FOR_REVIEW; no direct PUBLISHED without certification.
- Optimistic version checks on taxonomy reordering and status
  transitions. Audit INSERT occurs in the *same transaction* as
  all writes. Unauthorized/failed writes never create an audit entry.

## HTTP endpoints (feature opt-in)

`vr.content.admin.enabled=true` plus existing `vr.auth.issuer-uri` and
`vr.auth.audience`. DB settings `vr.content.admin.jdbc-url`,
`vr.content.admin.jdbc-user`, `vr.content.admin.jdbc-password`
must connect to a PostgreSQL with R5.1 then R5.2 migrations. No default
roles are seeded. **This is not a production-ready toggle** until IAM
enrollment/provisioning, DB permissions, quota and operational audit are
verified. Without `enabled=true`, admin REST routes are absent.

| Method | Route | Purpose |
|---|---|---|
| GET | `/api/v1/admin/content/worlds` | Authorized World listing |
| GET | `/api/v1/admin/content/worlds/{worldId}/areas` | Authorized Area listing |
| GET | `/api/v1/admin/content/areas/{areaId}/subjects` | Authorized Subject listing |
| GET | `/api/v1/admin/content/subjects/{subjectId}/lessons` | Authorized Lesson listing |
| POST | `/api/v1/admin/content/worlds` | PLATFORM_ADMIN creates World DRAFT |
| POST | `/api/v1/admin/content/worlds/{worldId}/areas` | CONTENT_ADMIN creates Area |
| POST | `/api/v1/admin/content/areas/{areaId}/subjects` | CONTENT_ADMIN creates Subject |
| POST | `/api/v1/admin/content/subjects/{subjectId}/lessons` | CONTENT_ADMIN/AUTHOR creates DRAFT lesson in exact Subject |
| POST | `/api/v1/admin/content/lessons/{lessonId}/placements/{subjectId}` | CONTENT_ADMIN adds placement |
| PUT | `/api/v1/admin/content/{kind}/{nodeId}/translations/{locale}` | Update localized title |
| PATCH | `/api/v1/admin/content/{kind}/{nodeId}/order` | Update sortOrder with expectedVersion |
| POST | `/api/v1/admin/content/{kind}/{nodeId}/transitions` | Allowed status change with expectedVersion |

Request create example:
```json
{"code":"SUBJECT_PHYSICS","sortOrder":20}
```
Status transition:
```json
{"state":"PUBLISHED","expectedVersion":0}
```

## Test coverage

- Integration, actual PostgreSQL 16: global vs World-scope roles,
  organization-scoped role denial, no self-grant endpoint,
  cross-world read and write denial, role revocation immediately
  effective, validated locale, transactional audit, duplicate rollback,
  stale optimistic version, mandatory 3 locales and published parent,
  archive child rule, additional Lesson placement authorization.
- Spring MockMvc + security-test: missing token 401, untrusted role
  claim cannot escalate, JWT subject taken from authenticated
  principal, actual admin service denies 403, uncertified lesson
  publication 409 and malformed translation 400.

## Blocking follow-up (R5.2 hardening / R5.3)

1. Trusted IAM enrollment/provisioning of `content.admin_membership`,
   multi-admin review and revocation audit; DB application role must not
   hold unrestricted INSERT/UPDATE privileges on admin memberships.
2. Complete admin edit API for category titles/descriptions, parent
   change decisions, translation optimistic locking and archival
   dependent assets; no hard delete by HTTP.
3. Replace reference DriverManager with pooled datasource and
   reviewed Liquibase changesets plus upgrade/backfill tests.
4. Full organization-scoped catalogs, private drafts and tenant isolation
   need explicit `organization_id` ownership in Content Domain and
   verified membership; currently organization-bound roles are DENIED.
5. R5.3 React Content Studio: role-aware navigation and forms; UI
   visibility never replaces server authorization.
6. R5.4 UploadSession must obtain immutable lesson+placement from the
   server rather than untrusted upload metadata.
7. Publication/RustFS/Quest Runtime remain separate R5.4–R5.7 tasks.

**Not implemented:** downloadable VR lesson publisher, multi-container
upload, natively verified Unreal Pak/IoStore, teacher portal, physical
Quest UAT. Green backend tests do not certify these features.
