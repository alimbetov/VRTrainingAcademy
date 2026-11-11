# R5.1 — PostgreSQL Content Catalog Foundation

Status: Backend PostgreSQL implementation, not an administrator UI or published catalog.

## Delivered source

- `backend/db/migrations/R5_1__content_catalog_foundation.sql`
- `backend/src/main/java/academy/vrtraining/platform/content/catalog/ContentCatalogStore.java`
- `backend/src/main/java/academy/vrtraining/platform/content/catalog/PostgresContentCatalogStore.java`
- PostgreSQL 16 integration tests at
  `backend/src/test/java/academy/vrtraining/platform/content/catalog/PostgresContentCatalogStoreIntegrationTest.java`

## Core guarantees

- Stable UUID for World, Area, Subject, Lesson; separate immutable ASCII code.
- Parent foreign keys and uniqueness: World→Area→Subject.
- Content lesson may appear in multiple subjects with ONE lesson UUID.
- Composite FKs `(world_id,area_id)` and `(area_id,subject_id)` in `lesson_placement`
  prevent a forged or cross-tree placement at PostgreSQL level, independent
  of application request validation.
- `createLessonInSubject` inserts lesson and exact placement in one
  transaction. Wrong-parent failure rolls back the entire lesson.
- Typed world/area/subject/lesson locale tables have real parent FKs,
  enforce `kk-KZ`, `ru-RU`, `en-US`, nonempty titles and unique locale.
- Sorted tree reads and subject lesson list are backed by PostgreSQL.
- No Spring controller or privileged API enabled: repository is not
  an authorization authority and must not be exposed without trusted
  CONTENT_ADMIN / CONTENT_AUTHOR membership checks.

## Exact scope limits (intentional)

- Migrations are reference SQL; production requires reviewed Liquibase
  migration with schema version checksum and upgrade/backfill tests.
- JDBC adapter uses `DriverManager` just like R3/R4 reference code; replace
  with a pooled DataSource before production.
- Status fields and version counters are persisted, but no approved
  status transition / optimistic update service exists yet.
- Localization supports persisted upsert but does not itself authorize
  publication of incomplete translations.
- R5.2 must implement role-scoped Content Studio administrative REST,
  revision/audit trail, taxonomy publish/archival policy and membership.
- R5.3 implements React Content Studio UI.
- R5.4 must create durable UploadSession linked to **server-owned**
  `lesson_id` and verified `subject placement`; never trust the
  caller-supplied `collectionId` in the existing legacy staging DTO.
- Licensable `Collection` differs from World/Area/Subject and is
  a separate content aggregate; its persistence and grant policies
  require follow-up design.
- R5.5+ publication requires certification and signed immutable releases;
  catalog rows alone grant no content downloads.
- No native Quest lesson mount/reopen claims from this foundation.

## Integration acceptance

PostgreSQL tests run when `VRTA_TEST_PG_JDBC_URL`,
`VRTA_TEST_PG_USER`, `VRTA_TEST_PG_PASSWORD` are defined.
GitHub Actions provisions PostgreSQL 16 and executes `mvn clean verify`.

Scenarios:
CAT-01 hierarchy persistence; CAT-02 exact subject placement;
CAT-03 same lesson in two subjects; CAT-04 forged cross-parent
rejection; CAT-05 duplicate placement; CAT-06 concurrent duplicate
placement; CAT-07 atomic creation rollback; CAT-08 persisted locales
and orphan rejection; CAT-09 sorted and scoped tree navigation.
Future authoring HTTP and frontend journeys remain separate RED tests.

**Decision:** suitable as an internal R5.1 data foundation pending exact
HEAD CI and independent review. Do not present as deployed admin CRUD.
