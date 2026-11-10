# VRTrainingAcademy R4 — licensing, Quest activation and content security review

**Scope:** Static review and automated Java/PostgreSQL contracts of Draft PR #8.
**Decision:** Conditional acceptance as a default-deny backend foundation only.
Live OAuth, publication authority and native Quest E2E remain **blocked**.

| Finding | Severity | Result | Required follow-up |
|---|---|---|---|
| SEC-R4-01: commercial downgrade leaves surplus registered devices eligible | P0 | **Fixed at online access check**: deterministic oldest-N device seat eligibility; PostgreSQL downgrade 2→1 and restore regression | Offline signed-lease revocation policy; historical approvals |
| SEC-R4-02: public device enrollment enabled alongside activation service | P0 | **Mitigated**: independent `VR_DEVICE_ACTIVATION_HTTP_ENABLED=false` flag | Real edge rate limits, IP/organization quotas, audit and alerting before enable |
| SEC-R4-03: device UUID is not bearer authentication | P0 | **Open**; domain method accepts *already verified* deviceId, no public device grant route | RFC 8628 Authorization Server integration + sender-constrained tokens and revocation checks |
| SEC-R4-04: persistent published Content Catalog missing | P0 | **Open**; publication adapters fail closed | Verify collection/lesson/release/manifest/tenant and signed artifacts |
| SEC-R4-05: presigned URLs remain usable until expiry after revocation | P0 | **Open**; existing 5-minute grant TTL helps but does not instant-revoke URLs | Define explicit short lease/expiry and online revocation contract |
| SEC-R4-06: device license seat checks must be atomic | P0 | **Verified**: organization-row lock serializes concurrent activations | Test production isolation and add DB pool |
| SEC-R4-07: Trial replay | P1 | **Verified**: unique OIDC owner, one lifetime trial for same owner | Rate limiting / proof organization re-registration across new identities |
| SEC-R4-08: real administrator / organization membership | P0 | **Open**; deny by default | OIDC claim validation + IAM-backed owner/admin membership |
| SEC-R4-09: deployment of schema changes | P1 | **Open**; SQL reference scripts only | Liquibase migration ordering, rollback and restart acceptance |
| SEC-R4-10: online/offline UX after downgrade | P1 | Current seat eligibility order uses activated_at then deviceId, no permanent suspension | Admin-controlled explicit seat assignments and notice to affected schools |

## Current invariants

- Account ID = canonical UUID; registered device tenant stored as `UUID.toString()`.
- License applies to organization, never a student or individual teacher.
- New Quest enrollment reserves a seat transactionally.
- Current online right requires ACTIVE registration + current license + a seat within
  the license cap + published collection entitlement.
- A device outside the current cap is **not deleted**; it becomes ineligible for
  new authenticated content requests until cap increases or another device is revoked.
- Public HTTP activation routes require both activation feature and separate HTTP
  feature flag. Do not enable without rate limiting.
- No key proof on subsequent requests has yet been implemented. A device UUID
  alone is not proof and must not be accepted as bearer credential.
- Existing R2 DownloadGrantService remains the transport authorization system;
  no replacement uploader/downloader is necessary.

## Required next qualification

1. RED tests and production adapter for device JWT sender constraint
   (proof possession, expired/forged token, revoked key, changed seat).
2. Signable, immutable published Content Catalog and school entitlements.
3. Real PostgreSQL / RustFS integration: grant→Range/ETag→signed manifest→asset.
4. Device seat downgrade and revocation while offline, with bounded expiry.
5. Edge abuse protection, explicit API error mapping, OIDC and administrative audit.
6. Liquibase, pooled JDBC, metrics, real Quest packaged end-to-end.

**Release classification:** Draft, no production flag activation and no merge
until the security gate owners independently qualify deployment behavior.
