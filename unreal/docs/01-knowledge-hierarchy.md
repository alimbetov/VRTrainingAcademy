# Knowledge taxonomy and experience graph — DEC-UE-001

Status: PROPOSED / R0 contract. Owner: Unreal Experience Runtime. Related baseline: feat/r0-platform-foundation.

## Two different graphs, never conflate them

**Discovery/navigation:** Knowledge Hub → World → Area → Subject → Lesson.
**Execution/assets:** Lesson → ExperienceDefinition → ModuleRelease(s) → platform-specific cooked artifact(s).

Navigation relationships are *placements*, not ownership of the actual lesson or module. A lesson can have several placements (e.g. fire safety under School and Factory) while keeping ONE stable lesson ID, local progress record and package dependencies. Server owns discovery/tenant visibility; client owns local cache state.

## Taxonomy

| Node | Definition | Example | Id | Parent |
|---|---|---|---|---|
| World | Audience/context space | School | WORLD_SCHOOL | Knowledge Hub |
| Area | Broad knowledge area | Natural sciences | AREA_NATSCI | World |
| Subject | Curriculum topic bucket | Physics | SUBJECT_PHYSICS | Area |
| Lesson | Verifiable learning goal | Simple circuit | PHY-001 | Subject via placement |
| Experience | Playable scenario specification | circuit-basics | EXP-CIRCUIT-1 | referenced by Lesson |
| Content module | Reusable scene/mechanics | electric-lab | MOD-ELECTRIC-LAB | referenced by Experience |
| Content release | Version/platform build | quest-v1 | REL-QUEST-... | owned by Module |

The initial hierarchy is exactly four visible levels. Future grade, country, curriculum, difficulty and organization are *facets* (filters), NOT mandatory extra parent levels. Worlds are lightweight catalog views, not loaded UE Levels.

## Rules and invariants

1. IDs: globally stable, immutable, ASCII codes; titles translated and mutable. A translation MUST NOT become an ID.
2. Navigation taxonomy is a rooted acyclic graph of category nodes. An explicit placement tuple `worldId, areaId, subjectId, lessonId` links a lesson into one path.
3. Area belongs to one World in R0; Subject belongs to one Area in R0; Lesson has 1..N placements. Introducing shared subject taxonomy later requires versioned migration.
4. Lesson definition includes learning objective, completion rule, assessment definition, accessibility metadata and one Experience ref. UI may show unavailable experience but must not fabricate entitlement.
5. Modules are not necessarily 1:1 with lessons: a room/lab may support many lessons and an Experience may reference many modules.
6. Category display order is deterministic (`sortOrder`, then `id`); title selection is `requested locale → en-US → first published locale`. Missing translation is logged, not silently marked published.
7. Non-accessible lessons may appear as `LOCKED` in discovery; actual grants are independently authorized by backend. Guest free catalog does NOT assert a valid paid grant.
8. Downloads/install-state are always local device facts; server catalog never claims content is already installed.
9. Avoid writing backend/education data into UE's private content structures; read versioned data contracts, no server implementation dependency.
10. Data must be validated for dangling references, duplicate IDs, duplicate placement tuples, invalid sort order, orphaned categories, locale fallback and cycles before rendering.
11. Releases pin `platform, runtimeBuildId, unrealEngineVersion, contentFormat, package dependencies`. Quest assets and Windows assets are not interchangeable.
12. Do not claim safety/medical curriculum certification based solely on program completion.

## Sample paths

- WORLD_SCHOOL → AREA_NATSCI → SUBJECT_ASTRONOMY → ASTRO-001 → EXP-SOLAR-TOUR → MOD-SOLAR-SYSTEM
- WORLD_SCHOOL → AREA_NATSCI → SUBJECT_PHYSICS → PHY-001 → EXP-CIRCUIT-BASICS → MOD-ELECTRIC-LAB
- WORLD_FACTORY → AREA_SAFETY → SUBJECT_HAZARDS → SAFE-001 → EXP-HAZARD-HUNT → MOD-WORKSHOP
- WORLD_SCHOOL → AREA_SAFETY → SUBJECT_HAZARDS → SAFE-001 (same Lesson id, second placement)

## UX behavior

1. Startup: load tiny bundled hub and last verified catalog snapshot.
2. Online: retrieve authorized catalog snapshot, validate and atomically replace last-good snapshot.
3. Render World portals → Area/Subject lists → Lesson cards. Never preload all 3D scenes.
4. Lesson card resolves Experience → dependency manifest → installed release registry. States: LOCKED / AVAILABLE / DOWNLOADING / VERIFYING / READY / UPDATE_AVAILABLE / ERROR.
5. Launch when READY: check entitlement/offline policy, verified release and module dependencies; mount compatible cooked content, open registered entry level, start deterministic assessment session.
6. Losing network retains last verified snapshot, installed free/bundled lessons and permitted offline-entitled lessons.

## Non-goals R0

No customer authentication implementation; no production package mount; no real Quest dynamic content claims; no Content Studio or curricular certification. First milestone is a browsable, tested in-memory fixture in Windows packaged build.
