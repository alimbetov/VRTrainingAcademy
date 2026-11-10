# VRTrainingAcademy — domain architecture (read with unified R0–R3 baseline)

## Product boundaries

- **Platform Core** (Spring Boot / PostgreSQL / RustFS): organization and device identity, entitlements, catalog publication, signed content grants, delivery manifests, offline licenses, auditing.
- **Education Domain** (Java + React/TypeScript): curricula, country-specific mappings, classes, assignments, lessons, assessment and progress; owns pedagogical metadata. Other domains must not read its tables directly.
- **Unreal Experience Runtime** (UE C++ / Blueprints): Knowledge Hub, input abstraction, interaction and simulation components, offline content manager, local persistence.

Professional/Safety is a future bounded context, not a subdomain of Education. Shared media/world assets belong to Platform's content contracts; learning goals belong to the respective domain.

```mermaid
flowchart LR
  Quest["Unreal Client (Quest/Windows)"] -->|Catalog / Entitlement / Results| Core["Spring Boot Modular Monolith"]
  Teacher["Teacher Portal (React)"] -->|Curriculum / Assignments| Edu["Education Domain"]
  Edu -->|Module APIs| Core
  Core --> PG[(PostgreSQL)]
  Core -->|Pre-sign only| S3[(RustFS / S3)]
  Quest -->|Scoped downloads| S3
  Quest -->|Runtime & Blueprints| World["Cooked Learning Worlds"]
```

## Domain ownership

1. Platform: Tenant, DeviceActivation, CatalogWorld, ContentModule, ContentRelease, ContentArtifact, Entitlement, DownloadGrant.
2. Education: Curriculum, Subject, LearningObjective, Lesson, Classroom, Assignment, Assessment, LearningAttempt.
3. Unreal: installed releases registry and checksummed local cache, display and user interaction. Does not grant its own permissions.

## Canonical integrated baseline

The earlier phase numbering in this architecture predates the executable R1
download manager, R2 delivery transport and R3 classroom/domain services.
Do **not** use the historical phase list as release status. The authoritative
implementation map and blockers are in
[Unified Platform R0–R3](unified-platform-r0-r3.md).

- R0: public demo taxonomy and browse-only metadata (no entitlement).
- R1: signed, verified **JVM on-device reference** delivery/install/cache.
- R2: Spring Backend Download Grant and RustFS direct/proxy transport.
- R3: teacher/Quest activation contracts, numeric learner classroom sessions,
  locale + lesson selection, daily statistics and 30-day retention policy.

## Non-negotiable constraints

- Never use login alone as authentication. Teacher/device activation can grant short-lived bounded sessions; protect admin actions with OIDC.
- Tenant context must be derived from authenticated claims, never arbitrary client input.
- SHA-256 detects corruption but does **not** prove authenticity: verify signed release manifest against pinned trusted key.
- Quest and Windows assets must be cooked separately and match runtime/engine/build.
- `pak`/IoStore download-and-mount on Quest is **unproven**; isolate behind content installer interface and require device acceptance.
- Platform Core must not depend on Education. Education may use Platform's published APIs/contracts.
- Multilingual content must be reviewed before marked published; kk-KZ, ru-RU, en-US first.
- No claims of certified safety/medical training without expert validation.

## Test-first delivery

1. Catalog contract tests and baseline compilation.
2. Persisted models with tenant isolation tests (Testcontainers/PostgreSQL).
3. License and grant security RED tests.
4. RustFS integration and corrupt package resilience tests.
5. Windows packaged dynamic content acceptance.
6. Quest real-device acceptance, with offline replay and rollback.
