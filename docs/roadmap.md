# Release roadmap

| Phase | Deliverable | Acceptance |
|---|---|---|
| R0 | Public read-only catalog + architecture + Unreal stub | 3 lessons across Education/Safety; RU/KK/EN tests |
| R1 | Tenant identity, education curriculum and license contracts | JWT scoped to tenant, real PostgreSQL + Testcontainers |
| R2 | RustFS object publishing and one-time download grants | Unauthorized access rejected; hashes/signatures verified |
| R3 | Cooked content on packaged Windows | Download, mount, launch, relaunch offline |
| R4 | Physical Quest R&D | Real APK installs compatible release and reopens offline |
| R5 | Teacher Portal / Content Studio | Teacher assigns and publishes curated learning content |

Never implement external-content execution by interpreting arbitrary downloaded C++ or Blueprints. Platform-specific cooked assets only; native code changes require a new application build.
