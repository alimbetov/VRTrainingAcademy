# DEC-EDU-04 — Number → Language → Lesson

Accepted VRTrainingAcademy classroom journey, 2026-11-10.
Supersedes the earlier proposal that the teacher sets a permanent Quest locale
at device activation. The teacher activates the **device for a school** only.

## Physical user journey

1. On a phone or PC the teacher/admin authorizes the Quest activation code.
2. Quest is registered to the organization and may receive assigned daily classroom sessions.
3. Student puts on Quest, types **numeric student number** (no name, nickname or password).
4. Student selects **language for this learning attempt**:
   Қазақша (kk-KZ), Русский (ru-RU), English (en-US).
5. Quest shows **available lessons** within the collection assigned to the
   classroom/session by an authorized teacher. This menu contains only lessons
   published, entitled to the school, available for the selected language and
   compatible with the Quest/runtime.
6. Student selects a lesson and starts a new attempt; Backend binds student UUID,
   daily session UUID, device UUID, selected language, lesson ID/revision and
   signed content release. Client uses R2 Download Grant for any missing assets.
7. Quest saves learning events offline if necessary; Backend reports only
   **today's results** by number, storing history for up to **30 days**.
8. Ending the attempt resets learner-specific locale/selection and temporary
   state; the next student can choose a different language without
   reactivating Quest or redownloading unrelated resources.

## Data boundaries

- Student number is classroom-scoped and not proof of identity.
- Locale and selected lesson belong to the **learning attempt**, not the
  registered device. No global device locale override by a learner.
- A teacher may assign a collection to a daily session; a student selects
  a **lesson from that collection**. The teacher can revise assignments.
- Backend must use the authenticated device, authorized classroom membership,
  teacher assignment, trusted publication and entitlements when filtering the
  list and when accepting the final selected lesson.
- A collection is not a RustFS object key, and the R0 public demo catalog
  does not prove entitlements. Published localized content is immutable and
  verifies Ed25519 manifest signature plus artifact SHA-256.
- Missing language variants are excluded for MVP, rather than silently
  playing a different language.
- Day boundary follows organization's IANA timezone. Result count/score
  starts from zero next day; cleanup after 30 days must not drop unacked
  offline events or data under hold.

## Code added in this PR

- `DeviceLearningSelectionPolicy.LearnerChoice` (studentId + sessionId +
  locale + lessonId), `LessonChoice`, and trusted candidate filtering.
- JUnit tests for per-attempt language, wrong collection, unpublished, missing
  entitlement and unsupported translation.
- `R3_4__device_locale_and_collection.sql` adds learner_locale and
  selected_lesson_id to attempts, plus assigned collection_id to session.
  Nullable columns preserve existing R3 rows.
- Existing PostgreSQL 16 integration CI applies migration R3_4.

## Implemented in the next R3 engineering slice

- `NumberLanguageLessonJourney` orchestrates authenticated device + student
  number + per-attempt locale + authorized lesson selection.
- `AuthorizedLessonCatalog` is a trust boundary; default adapter returns
  **no lessons**. The public R0 demo catalog is never used for authorization.
- `TeacherCollectionAssignmentService` validates tenant/teacher permission,
  assigned device, content entitlement and concurrent session state.
- `PostgresLearnerJourneyRepository` persists collection assignment and
  selected student UUID, locale, lesson ID and trusted release ID. Migration
  `R3_5__selected_lesson_release.sql` is required after R3_4.
- JUnit and live PostgreSQL integration tests cover wrong device, missing
  collection, unauthorized lesson and selected release persistence.
- `unreal/contracts/learner-journey.v1.example.json` and Python contract
  tests define three user screens followed by running state. These are
  **non-native contract tests**, not UE runtime UI.

## Still blocking production

- Trust-backed **Content Domain publication database** that actually
  produces localized published/entitled collections and lessons;
  real implementation of `AuthorizedLessonCatalog` and
  `AuthorizedCollectionAccess`. Their default behavior is deny.
- Teacher session scheduling still uses legacy `lessonId` and `lessonRevision`
  fields. Collection-first scheduling, selected lesson revision and content
  immutable manifest binding need a separate migration and review.
- Trusted sender-constrained device credentials and actual device REST
  endpoints (deliberately not published); signed session/attempt capabilities.
- Real Unreal UMG/Blueprint user interface and packaged Quest tests; currently
  no `.uproject`, no runnable native screen.
- Speech input, per-attempt offline outbox/reopen, automatic 30-day cleanup,
  actual publisher-backed lesson metadata and performance/abuse controls.
- PostgreSQL reference adapter uses DriverManager, not production pooling;
  publication/entitlement revocation race needs transaction-safe design.

No automatic 30-day cleanup worker is running. Do not describe this PR as
an end-to-end working Quest classroom journey.

Proposed endpoints (future; NOT exposed):
- `GET /api/v1/device/me/sessions/{id}/lessons?locale=kk-KZ`
- `POST /api/v1/device/me/sessions/{id}/attempts` with student number,
  locale and chosen lessonId, all verified server-side.
