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

## Not yet production-implemented

No real device UI, speech input, per-attempt selection API, persistent
Content Domain lesson/collection catalog, teacher collection assignment,
trusted sender-constrained device credentials or physical Quest E2E.
Current R3 session scheduling still expects an explicit lessonId. Integrating
a **session-level collection assignment** and **student-level lesson selection**
requires a reviewed change to those contracts plus acceptance tests.
No automatic 30-day cleanup worker is running.

Proposed endpoints (future; NOT exposed):
- `GET /api/v1/device/me/sessions/{id}/lessons?locale=kk-KZ`
- `POST /api/v1/device/me/sessions/{id}/attempts` with student number,
  locale and chosen lessonId, all verified server-side.
