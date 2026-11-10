# VRTrainingAcademy

A modular learning platform for classrooms using standalone Quest and
Windows VR: school-owned devices, numbered learners, multilingual lessons,
signed/on-demand content delivery, and day-scoped learning statistics.

## Status

**R0–R3 integrated foundation (NOT a production Quest application).**

The code contains Spring Boot 3.5 / Java 21 backend components and PostgreSQL
schema references, a **JVM reference content downloader**, signed content
verification/installation, teacher/classroom/numbered-student contracts,
device activation contracts, and Python-tested Unreal JSON fixtures.

There is **no runnable Unreal `.uproject`**, no real Quest UI, no configured
published-content/license adapter and no live device OAuth flow. Default
authorization for incomplete services is **DENY**.

## Canonical architecture

Read [Unified Platform Foundation R0–R3](docs/architecture/unified-platform-r0-r3.md)
for the full system map, product journey, component ownership, actual status,
security boundaries and P0/P1 release blockers.

```text
Teacher web/OIDC --school/device authorization--> Spring Boot Platform Core
   |                                               |
   +--assign classroom, collection, daily session--+-- PostgreSQL
                                                   |
Quest number -> language -> authorized lesson --> Education Domain
                                                   |
                                         Download Grant / entitlement
                                                   |
                                   DIRECT_RUSTFS | BACKEND_PROXY
                                                   |
                                  RustFS cooked signed release artifacts
                                                   |
                                  Quest native installer / offline outbox
```

## Existing modules

- `backend/src/main/java/academy/vrtraining/platform/delivery/`:
  signed manifests, Range resume, atomic JVM reference installer, LRU registry.
- `backend/src/main/java/academy/vrtraining/platform/delivery/grant/`:
  JWT download grants and direct RustFS / backend-proxy transport.
- `backend/src/main/java/academy/vrtraining/platform/device/`:
  Ed25519 device-binding activation and PostgreSQL registration.
- `backend/src/main/java/academy/vrtraining/education/classroom/`:
  numeric learners, teacher collection assignment, daily sessions,
  locale-scoped lesson selection and progress event contracts.
- `backend/db/migrations/`: reviewed SQL references, **not** yet managed
  by production Liquibase.
- `unreal/`: taxonomy, UI/client **sketches and test fixtures only**.

The `VRContentManager` Java class is **not deployed as Spring Boot API code**:
it is device-side behavior to port/qualify in native Unreal.

## Tests

```bash
cd backend
mvn -B --no-transfer-progress clean verify
cd ..
python3 -m unittest discover -s unreal/tests -p 'test_*.py' -v
```

PostgreSQL integration checks run in GitHub Actions using PostgreSQL 16.
Local integration tests require the `VRTA_TEST_PG_*` environment variables.
Do not enable the classroom/device feature flags without trusted identity
and entitlement adapters and reviewed schema deployment.

See [Roadmap](docs/roadmap.md), [Unreal Runtime](unreal/README.md), and
[Unified Architecture](docs/architecture/unified-platform-r0-r3.md).
