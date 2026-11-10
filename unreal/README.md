# Unreal Experience Runtime — structure-first R0

This folder currently contains **architecture, contracts and test fixtures**, not a generated or packaged Unreal project.

## Architecture map

- [Knowledge hierarchy](docs/01-knowledge-hierarchy.md): World → Area → Subject → Lesson and the separate reusable Experience → 3D Module → Release graph
- [Runtime contracts](docs/02-runtime-contracts.md): catalog/navigation/content/lesson subsystems, lifecycle and security boundaries
- [Unreal-only roadmap](docs/03-build-sequence.md): test-first implementation sequence
- [Catalog fixture](contracts/catalog.snapshot.v1.example.json): 3 free lessons, RU/KK/EN, a lesson placed in multiple navigation branches
- [C++ navigation types sketch](SourceSketch/VRKnowledgeCatalogTypes.h)
- [C++ content installer sketch](SourceSketch/VRContentInstaller.h)
- [Pure-data contract tests](tests/test_catalog_contract.py)

## Run taxonomy checks (no Unreal required)

```bash
python3 -m unittest discover -s unreal/tests -p 'test_*.py' -v
```

The tests validate the proposed JSON fixture. They are **not** UE Automation Tests or proof that the runtime is implemented.

## Scope boundary

No actual `.uproject`, Blueprint, 3D Level, mounted cooked content, backend adapter or Quest device test exists yet. The existing Spring Boot R0 catalog endpoint uses a simpler format than the v1 proposal. Align the wire contract in a separate reviewed change when starting integration.

Priority: approve the hierarchy and runtime contracts **before** generating Unreal source assets.
