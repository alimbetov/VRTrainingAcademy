# VRTrainingAcademy — R0–R4 Component Assessment and Integration Decision

Date: 2026-11-10. Scope: actual GitHub source from `main`, PR #7,
PR #8 and their converged integration branch. Ratings refer to **readiness
for a functioning end-to-end school deployment**, not code quality or team
productivity. Grade scale: 0=absent, 5=partially implemented/tested,
10=qualified on live supported production topology.

| Component | Readiness /10 | Evidence | Top gap / next acceptance |
|---|---:|---|---|
| R0 Knowledge taxonomy | 7 | World→Area→Subject→Lesson models, API fixture, Python contracts | Publishable canonical collection taxonomy and stable IDs |
| Organization onboarding | 6 | Verified-email JWT contract, Trial DB, HTTP + JUnit tests | Real OIDC enrollment/email flow, account recovery, anti-abuse |
| Organization roles and membership | 2 | Owner subject only, admin deny-by-default port | OWNER/ADMIN/TEACHER membership + trusted issuer and invitation lifecycle |
| Trial and manual licensing | 7 | 30-day Trial, DB transactions, request approval, concurrency tests | Multiple license precedence and renewal policy, admin web UI, billing evidence |
| Device activation and seat limits | 6 | Ed25519 challenge, one-use code, transactional seat lock, downgrade checks | Subsequent device-token PoP, rate limiting, active revocation proof |
| OAuth and device-bound authentication | 2 | Activation key proof only, no OAuth Device Grant endpoints or verified Quest API identity | Full RFC 8628 integration + sender-constrained tokens and every-call revocation |
| Content Catalog / publication | 2 | Public demo taxonomy, deny-by-default publication interfaces | Durable signed Collection→Lesson→Release→Manifest; publish approval and entitlement |
| Tenant entitlements | 4 | Active organization license → collection ID policy + default-deny publication | Real published content resolver, organization membership, license overlays |
| R1 signed-content downloader | 7 | JVM reference SHA-256, Ed25519, resumable download and atomic install tests | Actual Unreal C++ installer, cooked assets and native restart verification |
| R2 Download Grants | 6 | Signed release DTO, RustFS direct presign / proxy transport, JWT scope | Real publisher data, device grants, actual object store and signed URL lifecycle |
| R2 Backend Proxy / resume | 7 | Integrated PR #7 ETag/If-Range/416 implementation and controller tests | Actual RustFS transport parity, throughput and revocation/TTL semantics |
| R3 classroom and scheduling | 5 | Teacher endpoints, numbered learner, daily sessions, DB tests | Collection-first scheduling, canonical organization UUID, real teacher roles |
| Learner journey / attempt | 6 | Number→locale→authorized lesson domain, exact collection SQL guard and tests | Authenticated Quest API, immutable selected lesson revision and real catalog |
| Progress and assessment | 4 | Event UUID/idempotency and daily aggregate tests | Server-authoritative scoring, native offline outbox, sequence/backfill |
| Offline/reconciliation and retention | 2 | Offline signed permit contract and 30-day policy only | Local durable outbox, offline expiry, re-open, protected 30-day cleanup |
| PostgreSQL schema & persistence | 5 | Real PG16 integration tests, transactional activation and license approval | Production Liquibase, migration versions/upgrade, pooled DataSource |
| Security / operational observability | 3 | Default-deny services, HTTP checks, error boundaries | Rate limits, audit log, structured 4xx mapping, metrics, alerts, threat-model signoff |
| Unreal Experience Runtime | 1 | Sketch headers, JSON contract fixtures and Python validation | Actual .uproject, UMG screens, cooked release mount, Quest/Windows tests |
| CI and test automation | 6 | Java 21/Maven verify + PG16 + Python fixture tests, per-HEAD runs | Integrated Docker RustFS/IdP E2E, mutation/coverage, native build pipeline |
| System integration / deployment | 3 | Converged source branch, cross-context contracts; no full school E2E | Single trusted school journey with real storage/device and documented recovery |

## Overall opinion

**Architectural coherence: 6/10.** Bounded contexts and choice of a
modular monolith are reasonable, but an important identity and
publication authority are still fragmented across interfaces.

**Implemented backend foundation: 6/10.** Scope has valuable code and
regression tests, but its default-deny production dependencies are not
configured. The score is not equivalent to deployability.

**Operational MVP readiness: 3/10.** Real school trial, Quest OAuth,
signed content publication, complete teacher and native learner workflow
and physical device E2E are missing. Do not conflate CI PASS with readiness.

## Accepted product and security decisions

- Organization UUID is the **only canonical business tenant identity**;
  textual legacy tenant references need explicit migration/backfill.
- Company license belongs to organization; membership and device activation
  must survive teacher change.
- Single standard Trial: 30×24h starting at verified organization enrollment,
  2 device seats, DEMO collection (policy configurable after product approval).
- Commercial license requires trusted platform admin, published content
  evidence and auditable grant/renewal/revocation.
- Learner journey is number 100–9999 → per-attempt kk-KZ/ru-RU/en-US →
  lesson within teacher-assigned collection. Number **never authenticates**.
- R1 downloader and R2 grant/transport must be **reused**. Java reference
  loader does not constitute a native Unreal subsystem.
- Public code approval is not long-lived Quest authentication. Every device
  API must verify proof of registered key and current organization/license.
- Zero entitlement/publication adapter = **deny**. There is no insecure demo
  fallback.

## Gate to S1/S2 development

1. Consolidate PR #7 and #8 in an integration branch and obtain exact-HEAD
   Java/PG16/Unreal-fixture CI green; review the combined diff independently.
2. Freeze organization membership, effective-license precedence (overlap,
   upgrade, renewal, downgrade), collection-first scheduling contract.
3. RED test for cross-tenant, revoked/over-seat Quest, expired token and
   stale signed release; test real current PostgreSQL ownership relationships.
4. Implement trusted publication adapter and device identity; **then**
   expose the Quest lessons/attempt/grant endpoints.
5. Provide real RustFS/Quest/Windows acceptance, offline and teacher daily
   scores. Merge only foundation code with default-deny flags until certified.

**Decision:** merged-to-main immediately = NO-GO. Integration PR can remain
Draft with passing tests; a successful build does not close P0 release gaps.
