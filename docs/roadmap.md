# VRTrainingAcademy — single roadmap

Canonical statuses are based on executable source and repository tests, not
on prior branch names. R0/R1/R2/R3 designations identify established slices;
features across those slices form one modular platform.

| Stage | Existing foundation | Blocking production work |
|---|---|---|
| R0 — Knowledge taxonomy | World → Area → Subject → Lesson; R0 catalog fixture | Publication-backed authorized collections |
| R1 — Signed client content | Ed25519 manifest, SHA-256, Range resume, atomic install, local LRU (JVM reference) | Unreal C++ installer/mount, offline restart, golden vectors |
| R2 — Content transport | JWT Download Grant, DIRECT_RUSTFS and BACKEND_PROXY Spring MVC | Tenant/device entitlement adapters, proxy resume, real RustFS E2E |
| R3 — Classroom learning | Device activation service, numbered students, per-attempt locale, teacher collection selection, PostgreSQL attempts/events | OIDC Device Flow, verified device REST, publication catalog, Unreal UI/outbox |
| Q1 — Integrated school journey | Cross-domain contract docs and Java/Postgres tests | Actual teacher portal, Quest & Windows packaged E2E |
| Q2 — Production hardening | Default-deny feature gating | Security audit, migration management, scoring, retention, observability |

## Required qualification order

1. Freeze canonical `Content Collection → Lesson → Release → Manifest`
   contract and build persistent Content Domain + tenant entitlements.
2. Integrate OIDC teacher authorization, device-bound credentials and
   organization membership, then expose authenticated device endpoints.
3. Reuse R2 download grants and R1 downloader **without rebuilding them**;
   align DIRECT_RUSTFS and BACKEND_PROXY client protocols.
4. Implement native Unreal screens: number → language → lesson → launch,
   plus signed release verifier/installer and transactional offline outbox.
5. Demonstrate real PostgreSQL → Backend → RustFS → packaged Windows →
   physical Quest → offline reopen → daily statistics.
6. Implement 30-day retention worker with late-sync protection and
   validate deployment, resource limits and release rollback.

**Until those gates pass, the repository is an integrated foundation,
not a deployable school VR experience.**
