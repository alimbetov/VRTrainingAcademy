# Unreal-only delivery plan — test first

All PRs under `unreal/` unless a wire contract change is separately approved. Main app remains on current R0 branch; this proposal branches from `feat/r0-platform-foundation`.

## U0 — Design / tests-first
- Review taxonomy decisions and contracts.
- Add JSON fixture and validator tests: duplicate IDs; parent refs; duplicate placements; subject mismatch; locale fallback; order; shared lesson.
- No Unreal Engine tooling needed for pure data checks.

## U1 — Actual Unreal project and built-in Knowledge Hub
- Generate UE5 C++ project using editor; check in `.uproject`, source, config, maps and minimal assets, not Binaries/Intermediate/Saved.
- Create world-neutral `LV_KnowledgeHub`, `WBP_KnowledgeHub` and keyboard/mouse navigation.
- Catalog loaded from bundled validated fixture; packaged Windows smoke.
- RED/green automation tests of traversal and breadcrumbs.

## U2 — Scenario Runtime foundations
- C++ subsystems; Blueprint interfaces; explicit input abstraction for Desktop/VR.
- Bundled ASTRO-001 scene, 3 interactions, measurable objective, results save/reopen.
- Test input and scoring deterministically; acceptance in packaged Windows.

## U3 — Catalog HTTP adapter and offline cache
- Contract v1 mapping; locale fallback; atomic snapshot replacement.
- Test handling stale/newer/invalid catalog, HTTP 401/403, offline startup.

## U4 — Content pipeline R&D
- Minimal cooked extra level for packaged Windows; verify module deps, signature/hash, stable launch and update rollback.
- Separate feature gate `VR_DYNAMIC_CONTENT_QUEST` default off.
- Physical Quest packaged acceptance required before enabling dynamically mounted content.

## U5 — Production UX
- Comfort settings, snap turn/teleport as appropriate, subtitles, seated mode, accessibility, lifecycle re-entry, crash recovery and instrumentation.
- No giant hub streaming all environments; only lightweight world portal previews.

## Definition of done
- Build and test logs attached to PR, actual packaged artifacts verified.
- Source code and Blueprints reusable across at least two lessons.
- No new lesson requires copy/paste of core content transport or scoring.
- R0, U1..U4 claims match actual verified platform; **never** mark Quest dynamic content PASS from Editor simulation.
