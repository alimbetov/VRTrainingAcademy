# DEC-EDU-03 — Number-only learner selection and daily statistics

Accepted MVP product policy, 2026-11-10.

## User experience
- A teacher creates a classroom and allocates unique 3–4 digit numbers.
- A registered Quest assigned to the lesson shows a **number keypad**.
- A learner types (or, later, says) their number and starts the lesson.
- No student name, nickname, password, account, email or phone is mandatory.
- The number identifies a classroom slot, **not the person's verified identity**.
- The selected student has an immutable UUID in the backend; the number may
  be changed without relabeling earlier attempts. Do not use number as DB PK.
- Class-scoped numbers can be stable across sessions and different headsets.
  A teacher may change assignments deliberately.

## Daily statistics with 30-day retention
- A new DailySession is created each school day. Its results start at zero.
- Teacher's default report shows **current school-local day only**.
- Historical results remain stored up to 30 days, allowing delayed upload,
  troubleshooting and later retention export by authorized staff. The MVP
  does not need a monthly cumulative gradebook.
- A day is defined by the IANA timezone of the organization, not the server.
- Event deduplication uses UUID event ID + attempt sequence; one student's
  number in another classroom/date must not merge those statistics.
- A scheduled retention worker may delete acknowledged, expired events after
  30 days only if no legal/audit hold applies. No unacknowledged offline events
  are silently purged.
- Configure data minimization and minor-student privacy controls; short
  numbers are still linkable pseudonymous education data.

## Status of implementation
- `DailyStudentNumberPolicy` with validation, local-day boundary and
  retention guards, covered by JUnit.
- Optional PostgreSQL reference migration
  `R3_3__daily_student_numbers.sql` introduces student numbers and daily
  session metadata **without deleting legacy nickname records**.
- This is backward-compatible preparatory work. Existing R3 teacher endpoints
  still create nickname-based profiles and need a separately reviewed API
  change to allocate numbers and compute daily scoped reports.
- Speech recognition and Quest number-pad UI are not yet implemented.
- No retention scheduler is live. Do not claim 30-day deletion is active.
- Do not merge as production until application-level number creation,
  migration backfill, authorized daily reporting and offline-device tests pass.

## Acceptance tests still required
1. Unique number within classroom; same number allowed in another classroom.
2. Two devices in same school resolve the same classroom-stable student UUID.
3. New school-local day has zero results; yesterday's results are retained.
4. UTC-to-Asia/Almaty midnight boundary and DST/timezone edge cases.
5. Retry of yesterday's offline event does not appear in today's report.
6. Expired acknowledged history deleted; unacknowledged/protected preserved.
7. Mistyped/shared numbers do not provide access to other students' reports.
8. Switching student ends previous attempt and clears transient VR state.
