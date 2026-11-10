# VRTrainingAcademy — Backend verification matrix (R4)

Scope: automated unit/MVC/PostgreSQL tests of PR #8. This file is an
acceptance checklist, not a claim of physical Quest or live RustFS readiness.

| Area | Test class | Assertions / acceptance evidence |
|---|---|---|
| Organization / Trial | `OrganizationLicensingServiceContractTest` | Verified email+subject, 30-day window, 2 DEMO seats; reject malformed email, blank/oversized org, missing owner |
| License request | `OrganizationLicensingServiceContractTest` | Owner-scoped request; reject zero/excess devices, empty/invalid collections, cross-owner |
| License issue | `OrganizationLicensingServiceContractTest` | ADMIN only; deny unpublished/unrequested collection, excess seats, expired/long validity, preserve approved subset |
| PostgreSQL licensing | `PostgresLicenseStoreIntegrationTest` | One-time Trial, actual commercial priority, approval idempotency, concurrent approval, expiration instant boundary, suspended org, concurrent same-owner signups |
| Device activation cryptography | `DeviceActivationServiceTest` | Ed25519 possession required, wrong proof and replay denied |
| Device HTTP | `DeviceActivationHttpContractTest` | Public begin contract, invalid SPKI/base64, owner approval authenticated, proof receipt minimized, wrong proof 403, owner revoke |
| Feature OFF | `DeviceActivationEndpointsFailClosedTest`, `OrganizationLicensingApiFailClosedTest` | Routes absent/unauthorized by default |
| Device seats PostgreSQL | `PostgresClassroomStoreIntegrationTest` | Concurrent final seat, revoked seat replacement, expired Trial, active-device downgrade 2→1 and restore |
| Device entitlement | `LicensedDownloadEntitlementTest` | Active registered Quest only; deny over cap, revoked device, invalid/expired license, unlicensed collection |
| R2 Download Grant | `LicensedDownloadGrantBridgeTest` | Reuses existing R2 service; owner, tenant, collection, published release, TTL and expired Trial fail closed |
| Lesson sessions | `NumberLanguageLessonJourneyTest`, `PostgresClassroomStoreIntegrationTest` | Number → locale → authorized lesson, tenant and device isolation, day-scoped statistics |
| Signed/download reference | `SignedReleaseVerifier` via delivery tests, `RustFsRangeDownloaderTest`, `VRContentManagerTest` | JVM reference signature, download resume and installation only (NOT native Unreal) |

## Run locally

With Java 21 and Maven:

```bash
cd backend
mvn -B --no-transfer-progress clean verify
```

For PostgreSQL integration tests supply an isolated PostgreSQL 16 database
and these environment variables before running Maven:

```bash
export VRTA_TEST_PG_JDBC_URL='jdbc:postgresql://localhost:5432/vrta_test'
export VRTA_TEST_PG_USER='vrta_test'
export VRTA_TEST_PG_PASSWORD='REPLACE_LOCALLY'
cd backend
mvn -B --no-transfer-progress clean verify
```

**IMPORTANT:** Current SQL integration fixtures create/alter schemas in the
configured database. Use a **dedicated disposable test database**, never
production or shared development data. CI supplies PostgreSQL 16.

### Targeted commands

```bash
cd backend
mvn -B -Dtest=OrganizationLicensingServiceContractTest,LicensedDownloadEntitlementTest,LicensedDownloadGrantBridgeTest,DeviceActivationHttpContractTest test
mvn -B -Dtest=PostgresLicenseStoreIntegrationTest,PostgresClassroomStoreIntegrationTest test
```

If the PostgreSQL env vars are missing, annotated integration tests are
**SKIPPED**, not green PostgreSQL evidence. Read JUnit XML and the CI logs,
including skipped counts, before interpreting PASS.

## Remaining release gates — not covered by these tests

- Real OIDC issuer, verified account ownership, platform role and token
  audience/issuer with a deployed identity provider.
- RFC 8628 Device Authorization Flow and sender-constrained Quest tokens for
  every subsequent device call; no UUID-only trust.
- Persistent publishable Content Catalog, signed immutable manifest and
  school-entitlement adapters; current default-deny is intentional.
- End-to-end real Backend ↔ PostgreSQL ↔ RustFS direct/proxy transport,
  plus installed signed native Unreal/Quest package.
- API abuse/rate limits, robust data migrations/rollback, runtime observability.
- Offline lease expiry, revocation after license downgrade, consistent outbox
  recovery, real teacher UI; full test coverage cannot prove these exist.

**Release decision:** Do not claim production readiness or merge until the
remaining P0 trust and physical device acceptance gates are independently reviewed.
